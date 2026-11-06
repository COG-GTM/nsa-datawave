package datawave.microservice.authorization.config;

import java.util.List;

import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.AuthenticationUserDetailsService;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationProvider;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;

import datawave.microservice.authorization.jwt.JWTAuthenticationFilter;
import datawave.microservice.authorization.jwt.JWTAuthenticationProvider;
import datawave.microservice.authorization.preauth.AuthorizationProxiedEntityX509Filter;
import datawave.microservice.config.security.AllowedCallersFilter;
import datawave.microservice.config.security.JWTSecurityConfigurer;

/**
 * Configures security for the authorization service: JWT authentication plus proxied-entity X509 authentication with a local user lookup.
 */
@Order(SecurityProperties.BASIC_AUTH_ORDER - 3)
@Configuration
@EnableCaching
public class AuthorizationSecurityConfigurer extends JWTSecurityConfigurer {
    private final DatawaveSecurityProperties securityProperties;
    private final AuthenticationUserDetailsService<PreAuthenticatedAuthenticationToken> authenticationUserDetailsService;
    
    public AuthorizationSecurityConfigurer(DatawaveSecurityProperties securityProperties,
                    AuthenticationUserDetailsService<PreAuthenticatedAuthenticationToken> authenticationUserDetailsService,
                    JWTAuthenticationProvider jwtAuthenticationProvider) {
        super(securityProperties, jwtAuthenticationProvider);
        this.securityProperties = securityProperties;
        this.authenticationUserDetailsService = authenticationUserDetailsService;
    }
    
    @Override
    protected void configure(HttpSecurity http) throws Exception {
        super.configure(http);
        
        AuthorizationProxiedEntityX509Filter proxiedX509Filter = new AuthorizationProxiedEntityX509Filter(securityProperties.isUseTrustedSubjectHeaders(),
                        securityProperties.isProxiedEntitiesRequired(), securityProperties.isIssuersRequired(), getAuthenticationEntryPoint());
        proxiedX509Filter.setAuthenticationManager(authenticationManager());
        proxiedX509Filter.setContinueFilterChainOnUnsuccessfulAuthentication(false);
        http.addFilterAfter(proxiedX509Filter, JWTAuthenticationFilter.class);
    }
    
    @Override
    protected List<AuthenticationProvider> authenticationProviders() {
        List<AuthenticationProvider> providers = super.authenticationProviders();
        PreAuthenticatedAuthenticationProvider provider = new PreAuthenticatedAuthenticationProvider();
        provider.setPreAuthenticatedUserDetailsService(authenticationUserDetailsService);
        providers.add(provider);
        return providers;
    }
    
    @Override
    protected AllowedCallersFilter getAllowedCallersFilter(DatawaveSecurityProperties securityProperties) {
        return new AuthorizationAllowedCallersFilter(securityProperties, getAuthenticationEntryPoint());
    }
}
