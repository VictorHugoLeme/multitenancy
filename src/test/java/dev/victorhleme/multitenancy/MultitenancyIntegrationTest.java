package dev.victorhleme.multitenancy;

import dev.victorhleme.multitenancy.dtos.ProductDto;
import dev.victorhleme.multitenancy.dtos.TenantDto;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration tests covering the full tenant lifecycle and product isolation.
 *
 * <p>Tests are ordered to simulate a real usage sequence: provision tenants → populate data →
 * verify isolation → enable/disable via endpoint → enable/disable via direct DB update and revalidation.
 *
 * <p>The {@link JdbcTemplate} injected here uses the {@code AbstractRoutingDataSource} primary bean.
 * Since no {@code ScopedValue} tenant context is bound on the test thread, it falls back to the
 * management datasource ({@code db_tenants}), making direct {@code UPDATE tenant} calls work as expected.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MultitenancyIntegrationTest extends BaseIntegrationTest {

    @LocalServerPort
    private int port;

    private RestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate(new JdkClientHttpRequestFactory());
        restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(HttpStatusCode statusCode) {
                return false;
            }
        });
    }

    // -------------------------------------------------------------------------
    // Tenant creation
    // -------------------------------------------------------------------------

    @Test
    @Order(1)
    void createTenant_BRA_shouldProvisionDatabase() {
        var response = restTemplate.postForEntity(url("/v1/tenants"), new TenantDto("Brazil", "BRA"), TenantDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("BRA");
        assertThat(response.getBody().isActive()).isTrue();
    }

    @Test
    @Order(2)
    void createTenant_CAN_shouldProvisionDatabase() {
        var response = restTemplate.postForEntity(url("/v1/tenants"), new TenantDto("Canada", "CAN"), TenantDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("CAN");
        assertThat(response.getBody().isActive()).isTrue();
    }

    // -------------------------------------------------------------------------
    // Product creation and isolation
    // -------------------------------------------------------------------------

    @Test
    @Order(3)
    void createProducts_forBRA_shouldStoreInBraDatabase() {
        createProduct("BRA", "Keyboard", "Mechanical keyboard", new BigDecimal("39.90"));
        createProduct("BRA", "Monitor",  "4K monitor",          new BigDecimal("299.00"));

        ProductDto[] products = getProducts("BRA");
        assertThat(products).hasSize(2);
        assertThat(products).extracting(ProductDto::getName)
                .containsExactlyInAnyOrder("Keyboard", "Monitor");
    }

    @Test
    @Order(4)
    void createProducts_forCAN_shouldStoreInCanDatabase() {
        createProduct("CAN", "Mouse",   "Gaming mouse",              new BigDecimal("65.90"));
        createProduct("CAN", "Headset", "Noise-cancelling headset",  new BigDecimal("149.99"));

        ProductDto[] products = getProducts("CAN");
        assertThat(products).hasSize(2);
        assertThat(products).extracting(ProductDto::getName)
                .containsExactlyInAnyOrder("Mouse", "Headset");
    }

    @Test
    @Order(5)
    void tenantIsolation_BRA_shouldNotSeeCanProducts() {
        ProductDto[] products = getProducts("BRA");

        assertThat(products).extracting(ProductDto::getName)
                .containsExactlyInAnyOrder("Keyboard", "Monitor")
                .doesNotContain("Mouse", "Headset");
    }

    @Test
    @Order(6)
    void tenantIsolation_CAN_shouldNotSeeBraProducts() {
        ProductDto[] products = getProducts("CAN");

        assertThat(products).extracting(ProductDto::getName)
                .containsExactlyInAnyOrder("Mouse", "Headset")
                .doesNotContain("Keyboard", "Monitor");
    }

    // -------------------------------------------------------------------------
    // Enable / disable via HTTP endpoint
    // -------------------------------------------------------------------------

    @Test
    @Order(7)
    void disableTenant_BRA_viaEndpoint_shouldRejectProductRequests() {
        var disable = restTemplate.exchange(url("/v1/tenants/disable/BRA"), HttpMethod.PATCH, HttpEntity.EMPTY, Void.class);
        assertThat(disable.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        var products = restTemplate.exchange(url("/v1/products"), HttpMethod.GET, tenantRequest("BRA"), String.class);
        assertThat(products.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @Order(8)
    void enableTenant_BRA_viaEndpoint_shouldAcceptProductRequests() {
        var enable = restTemplate.exchange(url("/v1/tenants/enable/BRA"), HttpMethod.PATCH, HttpEntity.EMPTY, Void.class);
        assertThat(enable.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ProductDto[] products = getProducts("BRA");
        assertThat(products).hasSize(2);
    }

    // -------------------------------------------------------------------------
    // Enable / disable via direct database change + manual revalidation
    // -------------------------------------------------------------------------

    @Test
    @Order(9)
    void disableTenant_CAN_viaDirectDb_thenRevalidate_shouldRejectProductRequests() {
        jdbcTemplate.update("UPDATE tenant SET active = 0 WHERE code = ?", "CAN");

        var revalidate = restTemplate.postForEntity(url("/v1/tenants/revalidate-datasources"), null, Void.class);
        assertThat(revalidate.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        var products = restTemplate.exchange(url("/v1/products"), HttpMethod.GET, tenantRequest("CAN"), String.class);
        assertThat(products.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @Order(10)
    void enableTenant_CAN_viaDirectDb_thenRevalidate_shouldAcceptProductRequests() {
        jdbcTemplate.update("UPDATE tenant SET active = 1 WHERE code = ?", "CAN");

        var revalidate = restTemplate.postForEntity(url("/v1/tenants/revalidate-datasources"), null, Void.class);
        assertThat(revalidate.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ProductDto[] products = getProducts("CAN");
        assertThat(products).hasSize(2);
    }

    // -------------------------------------------------------------------------
    // Cross-tenant aggregation
    // -------------------------------------------------------------------------

    @Test
    @Order(11)
    void countAllTenantProducts_shouldReturnTotalAcrossAllTenants() {
        var response = restTemplate.getForEntity(url("/v1/general/products/count"), Long.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(4L);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private void createProduct(String tenantCode, String name, String description, BigDecimal price) {
        var dto = new ProductDto(name, description, price);
        var response = restTemplate.postForEntity(url("/v1/products"), new HttpEntity<>(dto, tenantHeaders(tenantCode)), ProductDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ProductDto[] getProducts(String tenantCode) {
        var response = restTemplate.exchange(url("/v1/products"), HttpMethod.GET, tenantRequest(tenantCode), ProductDto[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private HttpEntity<Void> tenantRequest(String tenantCode) {
        return new HttpEntity<>(null, tenantHeaders(tenantCode));
    }

    private HttpHeaders tenantHeaders(String tenantCode) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-Code", tenantCode);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
