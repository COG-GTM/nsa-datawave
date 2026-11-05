package datawave.microservice.http.converter.protostuff;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;

import datawave.microservice.authorization.config.DatawaveSecurityProperties;
import datawave.microservice.authorization.jwt.JWTAuthenticationProvider;
import datawave.microservice.config.security.JWTSecurityConfigurer;

/**
 * Test configurer that permits every request. It out-ranks {@link JWTSecurityConfigurer} so that it is the configurer applied to the filter chain.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@Configuration
@Profile("permitAllWebTest")
public class PermitAllSecurityConfigurer extends JWTSecurityConfigurer {
    
    public PermitAllSecurityConfigurer(DatawaveSecurityProperties securityProperties, JWTAuthenticationProvider jwtAuthenticationProvider) {
        super(securityProperties, jwtAuthenticationProvider);
    }
    
    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
    }
}
