package in.backend.gatekeeper.gateway.service;

import in.backend.gatekeeper.gateway.dto.requestDto.GatewayRoute;
import in.backend.gatekeeper.gateway.event.RateLimitResult;
import in.backend.gatekeeper.gateway.model.CompiledRoute;
import in.backend.gatekeeper.gateway.dto.responseDto.GatewayErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;


@Service
public class GatewayService {
    private final RouteRegistry routeRegistry;
    private final ObjectMapper objectMapper;
    private final RateLimitService rateLimitService;

    public GatewayService(RouteRegistry routeRegistry, ObjectMapper objectMapper, RateLimitService rateLimitService) {
        this.routeRegistry = routeRegistry;
        this.objectMapper = objectMapper;
        this.rateLimitService = rateLimitService;
    }

    public ResponseEntity<byte[]> handle(HttpServletRequest httpServletRequest) {
        //1. CompiledRoute for incoming HTTP Request.

        //2. TO find Rate limiting rules and apply it.

        // 3. Idempotency check.

        // 4. Header rules.

        // 5. Redirect our end point to target

        // 6. Header rules on Response.

        // 7. Store response if idempotency was enabled.

        // 8. Return result to client.


        String correlationId = correlationId(httpServletRequest);
        long startedAt = System.nanoTime();
        String outcome = "TARGET_ERROR";
        int statusCode = HttpStatus.INTERNAL_SERVER_ERROR.value();
        boolean rateLimited = false;
        HttpHeaders policyHeaders = new HttpHeaders();



        Optional<CompiledRoute> matchedRoute = routeRegistry.find(httpServletRequest.getRequestURI());
        if (!matchedRoute.isPresent()) {
            outcome = "ROUTE_NOT_FOUND";
            statusCode = HttpStatus.NOT_FOUND.value();

            return error(HttpStatus.NOT_FOUND, "No active route matches",
                    correlationId, null);
        }
        CompiledRoute compiledRoute = matchedRoute.get();
        GatewayRoute gatewayRoute = compiledRoute.getGatewayRoute();
        if(!gatewayRoute.getAllowedMethods().contains(httpServletRequest.getMethod())) {
            outcome = "METHOD_NOT_ALLOWED";
            statusCode = HttpStatus.METHOD_NOT_ALLOWED.value();

            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.ALLOW, String.join(", ", gatewayRoute.getAllowedMethods()));

            return error(HttpStatus.METHOD_NOT_ALLOWED, "HTTP method is not allowed for this route",
                    correlationId, headers);
        }

        // Checking Rate Limiting
        RateLimitResult rateLimit = rateLimitService.check(gatewayRoute, httpServletRequest.getRemoteAddr());

        policyHeaders = rateLimitHeaders(rateLimit);

        if(!rateLimit.isAllowed()) {
            rateLimited = true;
            outcome = "RATE_LIMITED";
            statusCode = HttpStatus.TOO_MANY_REQUESTS.value();

            policyHeaders.set(HttpHeaders.RETRY_AFTER,
                    String.valueOf(rateLimit.getRetryAfterSeconds()));

            return error(HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded",
                    correlationId, policyHeaders);
        }

        //Idempotency Check
        if(gatewayRoute.isIdempotencyEnabled()&&"POST".equals(httpServletRequest.getMethod())){

        }




    }

    private String correlationId(HttpServletRequest request) {
        String correlationId = request.getHeader("X-Correlation-ID");
        if (correlationId == null || correlationId.trim().isEmpty()) {
            return UUID.randomUUID().toString();
        }
        return correlationId;
    }
    private HttpHeaders rateLimitHeaders(RateLimitResult result) {
        HttpHeaders headers = new HttpHeaders();

        if (result.getLimit() > 0) {
            headers.set("X-RateLimit-Limit", String.valueOf(result.getLimit()));
            headers.set("X-RateLimit-Remaining", String.valueOf(result.getRemaining()));
        }

        return headers;
    }


    private ResponseEntity<byte[]> error(HttpStatus status,
                                         String message,
                                         String correlationId,
                                         HttpHeaders additionalHeaders) {

        GatewayErrorResponse response = new GatewayErrorResponse(status.value(), message,
                correlationId, LocalDateTime.now());

        HttpHeaders headers = additionalHeaders == null ? new HttpHeaders() : additionalHeaders;

        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Correlation-ID", correlationId);

        return ResponseEntity.status(status).headers(headers)
                .body(objectMapper.writeValueAsString(response).getBytes(StandardCharsets.UTF_8));
    }
}
