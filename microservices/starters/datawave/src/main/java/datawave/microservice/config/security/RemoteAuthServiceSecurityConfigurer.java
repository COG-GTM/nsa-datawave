package datawave.microservice.config.security;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.AuthenticationUserDetailsService;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationProvider;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;

import datawave.microservice.authorization.config.DatawaveSecurityProperties;
import datawave.microservice.authorization.jwt.JWTAuthenticationFilter;
import datawave.microservice.authorization.jwt.JWTAuthenticationProvider;
import datawave.microservice.authorization.preauth.ProxiedEntityX509Filter;
import datawave.microservice.authorization.service.RemoteAuthorizationServiceUserDetailsService;

/**
 * Extends {@link JWTSecurityConfigurer} to also accept client certificate / proxied entity authentication, looking users up with the remote authorization
 * service when no JWT was presented.
 */
@Profile(RemoteAuthorizationServiceUserDetailsService.ACTIVATION_PROFILE)
@Order(SecurityProperties.BASIC_AUTH_ORDER - 3)
@Configuration
@ConditionalOnWebApplication
public class RemoteAuthServiceSecurityConfigurer extends JWTSecurityConfigurer {
    private final DatawaveSecurityProperties securityProperties;
    private final AuthenticationUserDetailsService<PreAuthenticatedAuthenticationToken> authenticationUserDetailsService;
    
    public RemoteAuthServiceSecurityConfigurer(DatawaveSecurityProperties securityProperties,
                    AuthenticationUserDetailsService<PreAuthenticatedAuthenticationToken> authenticationUserDetailsService,
                    JWTAuthenticationProvider jwtAuthenticationProvider) {
        super(securityProperties, jwtAuthenticationProvider);
        this.securityProperties = securityProperties;
        this.authenticationUserDetailsService = authenticationUserDetailsService;
    }
    
    @Override
    protected void configure(HttpSecurity http) throws Exception {
        super.configure(http);
        
        ProxiedEntityX509Filter proxiedX509Filter = new ProxiedEntityX509Filter(securityProperties.isUseTrustedSubjectHeaders(),
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
}
