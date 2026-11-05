package datawave.microservice.config.security;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.preauth.AbstractPreAuthenticatedProcessingFilter;
import org.springframework.security.web.authentication.preauth.x509.X509AuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

import datawave.microservice.authorization.Http403ForbiddenEntryPoint;
import datawave.microservice.authorization.config.DatawaveSecurityProperties;
import datawave.microservice.authorization.jwt.JWTAuthenticationFilter;
import datawave.microservice.authorization.jwt.JWTAuthenticationProvider;
import datawave.microservice.authorization.service.RemoteAuthorizationServiceUserDetailsService;

/**
 * Configures the DataWave {@link org.springframework.security.web.SecurityFilterChain}. All requests are authenticated with a JWT supplied either in an
 * Authorization header or in a cookie. Spring Security 6 removed {@code WebSecurityConfigurerAdapter}, so this class is now a plain configurer bean: the
 * lowest-ordered {@code JWTSecurityConfigurer} in the context is applied to the single {@link HttpSecurity} by
 * {@link GlobalMethodSecurityConfigurer#datawaveSecurityFilterChain}, which mirrors the first-match semantics the ordered adapters previously had.
 */
@Profile("!" + RemoteAuthorizationServiceUserDetailsService.ACTIVATION_PROFILE)
@Order(SecurityProperties.BASIC_AUTH_ORDER - 2)
@Configuration
@ConditionalOnWebApplication
@ConditionalOnProperty(name = "security.jwt.enabled", matchIfMissing = true)
public class JWTSecurityConfigurer {
    private final DatawaveSecurityProperties securityProperties;
    private final JWTAuthenticationProvider jwtAuthenticationProvider;
    private final AuthenticationEntryPoint authenticationEntryPoint;
    private AuthenticationManager authenticationManager;
    
    public JWTSecurityConfigurer(DatawaveSecurityProperties securityProperties, JWTAuthenticationProvider jwtAuthenticationProvider) {
        this.securityProperties = securityProperties;
        this.jwtAuthenticationProvider = jwtAuthenticationProvider;
        this.authenticationEntryPoint = new Http403ForbiddenEntryPoint();
    }
    
    protected void configure(HttpSecurity http) throws Exception {
        AuthenticationManager authenticationManager = authenticationManager();
        http.authenticationManager(authenticationManager);
        
        http.authorizeHttpRequests(authorize -> {
            // Allow unauthenticated access to the health and info endpoints (anything else requires a manager role, if configured)
            authorize.requestMatchers(EndpointRequest.to("info", "health")).permitAll();
            if (!securityProperties.getManagerRoles().isEmpty()) {
                authorize.requestMatchers(EndpointRequest.toAnyEndpoint()).hasAnyAuthority(securityProperties.getManagerRoles().toArray(new String[0]));
            }
            // Everything else requires a fully authenticated user
            authorize.anyRequest().fullyAuthenticated();
        });
        
        if (securityProperties.isRequireSsl()) {
            http.requiresChannel(channel -> channel.anyRequest().requiresSecure());
        }
        
        JWTAuthenticationFilter jwtFilter = new JWTAuthenticationFilter(false, authenticationManager, authenticationEntryPoint);
        
        http.cors(Customizer.withDefaults());
        http.csrf(AbstractHttpConfigurer::disable);
        http.headers(headers -> headers.referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .contentSecurityPolicy(csp -> csp.policyDirectives("frame-ancestors 'self'")));
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        http.exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(authenticationEntryPoint));
        
        // Allow CORS requests
        AllowedCallersFilter allowedCallersFilter = getAllowedCallersFilter(securityProperties);
        http.addFilterBefore(allowedCallersFilter, X509AuthenticationFilter.class);
        // Extract principal information from the JWT (if any)
        http.addFilterAfter(jwtFilter, X509AuthenticationFilter.class);
        // Deny access to anyone with a denied access role
        DeniedAccessRoleFilter deniedAccessRoleFilter = new DeniedAccessRoleFilter(securityProperties);
        http.addFilterAfter(deniedAccessRoleFilter, AbstractPreAuthenticatedProcessingFilter.class);
    }
    
    protected AllowedCallersFilter getAllowedCallersFilter(DatawaveSecurityProperties securityProperties) {
        return new AllowedCallersFilter(securityProperties, authenticationEntryPoint);
    }
    
    /**
     * The {@link AuthenticationProvider}s that make up the {@link AuthenticationManager} for this filter chain. Subclasses that previously overrode
     * {@code configure(AuthenticationManagerBuilder)} should override this method and add their providers to the returned list.
     */
    protected List<AuthenticationProvider> authenticationProviders() {
        List<AuthenticationProvider> providers = new ArrayList<>();
        providers.add(jwtAuthenticationProvider);
        return providers;
    }
    
    protected final AuthenticationManager authenticationManager() {
        if (authenticationManager == null) {
            authenticationManager = new ProviderManager(authenticationProviders());
        }
        return authenticationManager;
    }
    
    protected AuthenticationEntryPoint getAuthenticationEntryPoint() {
        return authenticationEntryPoint;
    }
}
