package com.keyloop.scheduler.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.keyloop.scheduler.IntegrationTestSupport;
import com.keyloop.scheduler.common.constants.ErrorMessageConstants;
import com.keyloop.scheduler.common.constants.HeaderConstants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ErrorHandlingIT extends IntegrationTestSupport {

  @Autowired private JsonMapper jsonMapper;

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
  void should_echo_request_id_header_on_actuator_health() {
    ResponseEntity<String> response =
        http.get().uri("/actuator/health").retrieve().toEntity(String.class);

    assertThat(response.getHeaders().getFirst(HeaderConstants.REQUEST_ID)).isNotBlank();
  }
}
