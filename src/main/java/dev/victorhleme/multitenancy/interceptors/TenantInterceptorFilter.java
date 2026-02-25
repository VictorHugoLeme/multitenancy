package dev.victorhleme.multitenancy.interceptors;

import dev.victorhleme.multitenancy.services.TenantService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Servlet filter that resolves the current tenant from the {@value TENANT_HEADER} request header
 * and binds it as the active tenant for the duration of the request.
 *
 * <p>Uses {@link TenantService#runWithTenant} to wrap {@link FilterChain#doFilter} inside a
 * {@link ScopedValue} scope, so every downstream component (controllers, repositories) automatically
 * routes to the correct tenant datasource without any extra plumbing.
 *
 * <p>Requests to {@code /v1/tenants/**} are excluded — those operate against the management
 * database and do not require a tenant context.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantInterceptorFilter extends OncePerRequestFilter {

    public static final String TENANT_HEADER = "X-Tenant-Code";

    private final TenantService tenantService;

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        List<String> excludedPaths = List.of("/v1/tenants", "/v1/general");
        return excludedPaths.stream().anyMatch(path -> request.getRequestURI().startsWith(path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        String tenantCode = request.getHeader(TENANT_HEADER);

        if (tenantCode == null || tenantCode.isBlank()) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST,
                "Missing required header: " + TENANT_HEADER);
            return;
        }

        if (!tenantService.tenantExistsByActive(tenantCode, true)) {
            log.warn("Rejected request — tenant [{}] not found or inactive", tenantCode);
            response.sendError(HttpServletResponse.SC_NOT_FOUND,
                "Tenant [%s] not found or inactive".formatted(tenantCode));
            return;
        }

        // Checked exceptions cannot propagate through a Runnable, so we capture them
        // and re-throw after the scoped-value call exits.
        IOException[] ioEx = {null};
        ServletException[] servletEx = {null};

        tenantService.runWithTenant(tenantCode, () -> {
            try {
                filterChain.doFilter(request, response);
            } catch (IOException e) {
                ioEx[0] = e;
            } catch (ServletException e) {
                servletEx[0] = e;
            }
        });

        if (ioEx[0] != null) throw ioEx[0];
        if (servletEx[0] != null) throw servletEx[0];
    }
}