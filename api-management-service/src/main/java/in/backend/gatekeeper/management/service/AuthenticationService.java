package in.backend.gatekeeper.management.service;

import in.backend.gatekeeper.management.dto.requestDto.LoginRequest;
import in.backend.gatekeeper.management.dto.requestDto.TenantAdminRequest;
import in.backend.gatekeeper.management.dto.responseDto.LoginResponse;
import in.backend.gatekeeper.management.entity.ManagementUser;
import in.backend.gatekeeper.management.entity.OrganizationStatus;
import in.backend.gatekeeper.management.exception.ApiException;
import in.backend.gatekeeper.management.repository.ManagementUserRepository;

import jakarta.validation.Valid;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class AuthenticationService {

    private final ManagementUserRepository managementUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final long expirationSeconds;


    public AuthenticationService(
            ManagementUserRepository managementUserRepository,
            PasswordEncoder passwordEncoder,
            JwtEncoder jwtEncoder,
            @Value("${security.jwt.expiration-seconds}") long expirationSeconds) {

        this.managementUserRepository = managementUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.expirationSeconds = expirationSeconds;

    }

    @Transactional(readOnly = true)
    public LoginResponse login(@Valid LoginRequest loginRequest) {

        // Login flow
        //String rawPassword = "platform123";

        //String newHash = passwordEncoder.encode(rawPassword);

        //System.out.println("NEW HASH: " + newHash);

        //System.out.println(
               // "MATCH: " + passwordEncoder.matches(rawPassword, newHash)
        //);

        // 1. Find user by username
        ManagementUser user = managementUserRepository
                .findByUsername(loginRequest.getUsername())
                .orElseThrow(() ->
                        new BadCredentialsException("Invalid username or password"));


        // 2. Verify password
        boolean passwordMatches = passwordEncoder.matches(
                loginRequest.getPassword(),
                user.getPasswordHash()
        );

        System.out.println("PASSWORD MATCH: " + passwordMatches);

        if (!user.isEnabled()) {
            throw new DisabledException("User account is disabled");
        }

        if (!passwordMatches) {
            throw new BadCredentialsException("Invalid username or password");
        }

        // 3. Check organization status
        if (user.getOrganization() != null &&
                user.getOrganization().getStatus() != OrganizationStatus.ACTIVE) {

            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "Organization is not active"
            );
        }

        // 4. Generate JWT token
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(expirationSeconds);

        JwtClaimsSet.Builder claimsBuilder = JwtClaimsSet.builder()
                .issuer("gatekeeper")
                .issuedAt(now)
                .expiresAt(expiry)
                .subject(user.getUsername())
                .claim("role", user.getRole().getName());

        if (user.getOrganization() != null) {
            claimsBuilder.claim("organizationId", user.getOrganization().getId());
        }

        JwtClaimsSet claims = claimsBuilder.build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();

        String token = jwtEncoder.encode(
                JwtEncoderParameters.from(header, claims)
        ).getTokenValue();

        // 5. Return login response
        return new LoginResponse(
                token,
                "Bearer",
                expirationSeconds,
                user.getRole().getName(),
                user.getOrganization() != null
                        ? user.getOrganization().getId()
                        : null
        );

    }

}