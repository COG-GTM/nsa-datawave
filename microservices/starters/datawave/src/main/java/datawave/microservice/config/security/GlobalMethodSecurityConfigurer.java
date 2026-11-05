package datawave.microservice.config.security;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.security.servlet.PathRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Enables web and method security for DataWave microservices and builds the single {@link SecurityFilterChain} from the highest-precedence (lowest
 * {@code @Order}) {@link JWTSecurityConfigurer} bean in the context. Before Spring Security 6, each configurer was a {@code WebSecurityConfigurerAdapter}
 * matching every request, so only the lowest-ordered adapter ever applied; this reproduces that behaviour without registering multiple any-request chains
 * (which Spring Security 6 rejects).
 */
@Configuration
@EnableWebSecurity
@ConditionalOnWebApplication
@EnableMethodSecurity(securedEnabled = true)
public class GlobalMethodSecurityConfigurer {
    
    @Bean
    public SecurityFilterChain datawaveSecurityFilterChain(HttpSecurity http, List<JWTSecurityConfigurer> securityConfigurers) throws Exception {
        if (securityConfigurers.isEmpty()) {
            // Equivalent to Spring Boot's default when no adapter was registered: everything authenticated with basic/form login.
            http.authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated());
            http.httpBasic(Customizer.withDefaults());
            http.formLogin(Customizer.withDefaults());
        } else {
            AnnotationAwareOrderComparator.sort(securityConfigurers);
            securityConfigurers.get(0).configure(http);
        }
        return http.build();
    }
    
    @Bean
    public WebSecurityCustomizer datawaveStaticResourcesWebSecurityCustomizer() {
        return web -> web.ignoring().requestMatchers(PathRequest.toStaticResources().atCommonLocations());
    }
}
