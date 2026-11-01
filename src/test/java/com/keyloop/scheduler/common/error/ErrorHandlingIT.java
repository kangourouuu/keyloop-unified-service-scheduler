package com.keyloop.scheduler.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.keyloop.scheduler.IntegrationTestSupport;
import com.keyloop.scheduler.common.constants.ApiPathConstants;
import com.keyloop.scheduler.common.constants.ErrorMessageConstants;
import com.keyloop.scheduler.common.constants.HeaderConstants;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

class ErrorHandlingIT extends IntegrationTestSupport {

  @Test
  void should_return_sanitized_500_whose_request_id_matches_the_header() {
    ResponseEntity<String> response =
        http.get().uri("/api/test/boom").retrieve().toEntity(String.class);
    JsonNode body = jsonMapper.readTree(response.getBody());

    assertThat(response.getStatusCode().value()).isEqualTo(500);
    assertThat(body.get("code").asString()).isEqualTo("KUSS_INTERNAL_ERROR");
    assertThat(body.get("detail").asString()).isEqualTo(ErrorMessageConstants.UNEXPECTED_ERROR);
    assertThat(body.get("requestId").asString())
        .isEqualTo(response.getHeaders().getFirst(HeaderConstants.REQUEST_ID));
  }

  @Test
  void should_return_404_resource_not_found_for_unknown_path() {
    ResponseEntity<String> response =
        http.get().uri("/api/v1/does-not-exist").retrieve().toEntity(String.class);
    JsonNode body = jsonMapper.readTree(response.getBody());

    assertThat(response.getStatusCode().value()).isEqualTo(404);
    assertThat(body.get("code").asString()).isEqualTo("KUSS_RESOURCE_NOT_FOUND");
  }

  @Test
  void should_return_404_problem_detail_for_a_direct_error_endpoint_request() {
    ResponseEntity<String> response = http.get().uri("/error").retrieve().toEntity(String.class);
    JsonNode body = jsonMapper.readTree(response.getBody());

    assertThat(response.getStatusCode().value()).isEqualTo(404);
    assertThat(response.getHeaders().getContentType().toString())
        .startsWith("application/problem+json");
    assertThat(body.get("code").asString()).isEqualTo("KUSS_RESOURCE_NOT_FOUND");
    assertThat(body.get("requestId").asString())
        .isEqualTo(response.getHeaders().getFirst(HeaderConstants.REQUEST_ID));
  }

  @Test
  void should_return_problem_detail_for_container_level_bad_requests() throws IOException {
    for (String target :
        List.of(ApiPathConstants.DEALERSHIPS + "/%00", ApiPathConstants.DEALERSHIPS + "/%ZZ")) {
      String response = rawGet(target);

      assertThat(response).startsWith("HTTP/1.1 400");
      assertThat(response).contains("application/problem+json").contains("KUSS_VALIDATION_FAILED");
      assertThat(response).doesNotContain("<html");
    }
  }

  private String rawGet(String target) throws IOException {
    try (Socket socket = new Socket("localhost", port)) {
      socket
          .getOutputStream()
          .write(
              ("GET " + target + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                  .getBytes(StandardCharsets.US_ASCII));
      return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void should_echo_request_id_header_on_actuator_health() {
    ResponseEntity<String> response =
        http.get().uri("/actuator/health").retrieve().toEntity(String.class);

    assertThat(response.getHeaders().getFirst(HeaderConstants.REQUEST_ID)).isNotBlank();
  }
}
