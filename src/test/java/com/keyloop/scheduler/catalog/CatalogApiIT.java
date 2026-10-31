package com.keyloop.scheduler.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.keyloop.scheduler.IntegrationTestSupport;
import com.keyloop.scheduler.SeedIds;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class CatalogApiIT extends IntegrationTestSupport {

  @Autowired private JsonMapper jsonMapper;

  @Test
  void should_list_the_two_seeded_dealerships() {
    assertThat(jsonOf("/api/v1/dealerships").size()).isEqualTo(2);
  }

  @Test
  void should_list_only_the_four_active_service_types() {
    assertThat(jsonOf("/api/v1/service-types").size()).isEqualTo(4);
  }

  @Test
  void should_return_london_detail_with_technician_skills_mapped() {
    JsonNode detail = jsonOf("/api/v1/dealerships/" + SeedIds.LONDON_DEALERSHIP_ID);

    assertThat(detail.get("bays").size()).isEqualTo(4);
    assertThat(skillsOf(detail, SeedIds.LONDON_TECH_1_ID))
        .containsExactlyInAnyOrder("OIL_CHANGE", "TIRE_SERVICE", "BRAKE_REPAIR");
  }

  @Test
  void should_return_404_for_unknown_dealership() {
    ResponseEntity<String> response =
        http.get()
            .uri("/api/v1/dealerships/" + UUID.randomUUID())
            .retrieve()
            .toEntity(String.class);

    assertThat(response.getStatusCode().value()).isEqualTo(404);
  }

  @Test
  void should_list_customers_with_vehicles_exposing_only_public_fields() {
    JsonNode customers = jsonOf("/api/v1/customers");

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
    for (JsonNode technician : detail.get("technicians")) {
      if (technician.get("id").asString().equals(technicianId.toString())) {
        return Set.copyOf(readStrings(technician.get("skillCodes")));
      }
    }
    throw new AssertionError("technician not found: " + technicianId);
  }

  private static List<String> readStrings(JsonNode array) {
    List<String> values = new ArrayList<>();
    array.forEach(node -> values.add(node.asString()));
    return values;
  }

  private static Set<String> propertyNames(JsonNode node) {
    return Set.copyOf(node.propertyNames());
  }
}
