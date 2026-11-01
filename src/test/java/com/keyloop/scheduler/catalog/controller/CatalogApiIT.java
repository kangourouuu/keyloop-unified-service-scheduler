package com.keyloop.scheduler.catalog.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.keyloop.scheduler.IntegrationTestSupport;
import com.keyloop.scheduler.SeedIds;
import com.keyloop.scheduler.common.constants.ApiPathConstants;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

class CatalogApiIT extends IntegrationTestSupport {

  @Test
  void should_list_the_two_seeded_dealerships() {
    assertThat(jsonOf(ApiPathConstants.DEALERSHIPS).size()).isEqualTo(2);
  }

  @Test
  void should_list_only_the_four_active_service_types() {
    assertThat(jsonOf(ApiPathConstants.SERVICE_TYPES).size()).isEqualTo(4);
  }

  @Test
  void should_return_london_detail_with_technician_skills_mapped() {
    JsonNode detail = jsonOf(ApiPathConstants.DEALERSHIPS + "/" + SeedIds.LONDON_DEALERSHIP_ID);

    assertThat(detail.get("bays").size()).isEqualTo(4);
    assertThat(skillsOf(detail, SeedIds.LONDON_TECH_1_ID))
        .containsExactlyInAnyOrder("OIL_CHANGE", "TIRE_SERVICE", "BRAKE_REPAIR");
  }

  @Test
  void should_expose_is_active_on_bays_and_technicians() {
    JsonNode detail = jsonOf(ApiPathConstants.DEALERSHIPS + "/" + SeedIds.LONDON_DEALERSHIP_ID);

    assertThat(propertyNames(detail.get("bays").get(0)))
        .contains("isActive")
        .doesNotContain("active");
    assertThat(propertyNames(detail.get("technicians").get(0)))
        .contains("isActive")
        .doesNotContain("active");
    assertThat(detail.get("bays").get(0).get("isActive").asBoolean()).isTrue();
  }

  @Test
  void should_return_404_for_unknown_dealership() {
    ResponseEntity<String> response =
        http.get()
            .uri(ApiPathConstants.DEALERSHIPS + "/" + UUID.randomUUID())
            .retrieve()
            .toEntity(String.class);

    assertThat(response.getStatusCode().value()).isEqualTo(404);
  }

  @Test
  void should_list_customers_with_vehicles_exposing_only_public_fields() {
    JsonNode customers = jsonOf(ApiPathConstants.CUSTOMERS);

    assertThat(customers.size()).isEqualTo(3);
    JsonNode customer = customers.get(0);
    assertThat(propertyNames(customer)).containsExactlyInAnyOrder("id", "fullName", "vehicles");
    assertThat(propertyNames(customer.get("vehicles").get(0)))
        .containsExactlyInAnyOrder("id", "make", "model", "modelYear");
  }

  private JsonNode jsonOf(String uri) {
    return jsonMapper.readTree(http.get().uri(uri).retrieve().toEntity(String.class).getBody());
  }

  private static Set<String> skillsOf(JsonNode detail, UUID technicianId) {
    return detail
        .get("technicians")
        .valueStream()
        .filter(technician -> technician.get("id").asString().equals(technicianId.toString()))
        .findFirst()
        .map(technician ->
            technician
                .get("skillCodes")
                .valueStream()
                .map(JsonNode::asString)
                .collect(Collectors.toSet()))
        .orElseThrow(() -> new AssertionError("technician not found: " + technicianId));
  }

  private static Set<String> propertyNames(JsonNode node) {
    return Set.copyOf(node.propertyNames());
  }
}
