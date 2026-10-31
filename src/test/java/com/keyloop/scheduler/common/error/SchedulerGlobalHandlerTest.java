package com.keyloop.scheduler.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.keyloop.scheduler.common.constants.HeaderConstants;
import com.keyloop.scheduler.common.constants.RequestConstants;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

class SchedulerGlobalHandlerTest {

  private static final String REQUEST_ID = "req-1";

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(new StubController())
            .setControllerAdvice(new SchedulerGlobalHandler(new SimpleMeterRegistry()))
            .build();
  }

  @ParameterizedTest
  @EnumSource(ErrorCode.class)
  void should_return_problem_with_status_and_code_for_each_scheduler_error_code(ErrorCode code)
      throws Exception {
    mockMvc
        .perform(
            get("/stub/codes/{code}", code)
                .requestAttr(RequestConstants.REQUEST_ID_ATTRIBUTE, REQUEST_ID))
        .andExpect(status().is(code.status().value()))
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.code").value(code.name()))
        .andExpect(jsonPath("$.requestId").value(REQUEST_ID));
  }

  @ParameterizedTest
  @MethodSource("overloadExceptions")
  void should_return_503_service_busy_with_retry_after_when_the_database_is_overloaded(
      Exception overload) throws Exception {
    MvcResult result =
        mockMvc
            .perform(get("/stub/overload").requestAttr("overload", overload))
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Retry-After", "1"))
            .andExpect(jsonPath("$.code").value("KUSS_SERVICE_BUSY"))
            .andExpect(jsonPath("$.detail").value("Service is busy. Retry later."))
            .andReturn();

    assertThat(result.getRequest().getAttribute(RequestConstants.ERROR_CODE_ATTRIBUTE))
        .isEqualTo("KUSS_SERVICE_BUSY");
  }

  static Stream<Exception> overloadExceptions() {
    return Stream.of(
        new CannotCreateTransactionException("pool timeout"),
        new QueryTimeoutException("canceling statement due to user request"));
  }

  @Test
  void should_return_503_with_retry_after_when_service_is_busy() throws Exception {
    MvcResult result =
        mockMvc
            .perform(get("/stub/codes/{code}", ErrorCode.KUSS_SERVICE_BUSY))
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Retry-After", "1"))
            .andExpect(jsonPath("$.code").value("KUSS_SERVICE_BUSY"))
            .andReturn();

    assertThat(result.getRequest().getAttribute(RequestConstants.ERROR_CODE_ATTRIBUTE))
        .isEqualTo("KUSS_SERVICE_BUSY");
  }

  @Test
  void should_not_send_retry_after_for_other_scheduler_errors() throws Exception {
    mockMvc
        .perform(get("/stub/codes/{code}", ErrorCode.KUSS_NO_CAPACITY))
        .andExpect(header().doesNotExist("Retry-After"));
  }

  @Test
  void should_return_400_idempotency_key_missing_when_header_absent() throws Exception {
    mockMvc
        .perform(post("/stub/booking"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("KUSS_IDEMPOTENCY_KEY_MISSING"));
  }

  @Test
  void should_return_400_validation_failed_naming_the_field_when_body_is_empty() throws Exception {
    mockMvc
        .perform(post("/stub/validated").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("KUSS_VALIDATION_FAILED"))
        .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("id")));
  }

  @Test
  void should_return_400_validation_failed_when_json_is_malformed() throws Exception {
    mockMvc
        .perform(
            post("/stub/validated").contentType(MediaType.APPLICATION_JSON).content("{bad json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("KUSS_VALIDATION_FAILED"))
        .andExpect(jsonPath("$.detail").value("Malformed request."));
  }

  @Test
  void should_return_400_validation_failed_when_request_param_has_wrong_type() throws Exception {
    mockMvc
        .perform(get("/stub/number").param("n", "abc"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("KUSS_VALIDATION_FAILED"));
  }

  @Test
  void should_return_405_with_allow_header_when_method_is_not_supported() throws Exception {
    mockMvc
        .perform(post("/stub/number"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(header().string("Allow", "GET"))
        .andExpect(jsonPath("$.code").value("KUSS_VALIDATION_FAILED"));
  }

  @Test
  void should_return_400_validation_failed_when_constraint_violation_is_thrown() throws Exception {
    mockMvc
        .perform(get("/stub/constraint"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("KUSS_VALIDATION_FAILED"));
  }

  @Test
  void should_return_500_internal_error_without_leaking_exception_text() throws Exception {
    MvcResult result =
        mockMvc.perform(get("/stub/boom")).andExpect(status().isInternalServerError()).andReturn();

    String body = result.getResponse().getContentAsString();
    assertThat(body)
        .contains("KUSS_INTERNAL_ERROR")
        .doesNotContain("SELECT")
        .doesNotContain("IllegalStateException");
    assertThat(result.getRequest().getAttribute(RequestConstants.ERROR_CODE_ATTRIBUTE))
        .isEqualTo("KUSS_INTERNAL_ERROR");
  }

  @Test
  void should_keep_java_and_framework_types_out_of_4xx_details() throws Exception {
    String malformed =
        mockMvc
            .perform(
                post("/stub/validated")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{bad json"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String wrongType =
        mockMvc
            .perform(get("/stub/number").param("n", "abc"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(malformed + wrongType).doesNotContain("java.", "com.keyloop", "tools.jackson");
  }

  @Test
  void should_set_error_code_request_attribute_for_scheduler_errors() throws Exception {
    MvcResult result = mockMvc.perform(post("/stub/booking")).andReturn();

    assertThat(result.getRequest().getAttribute(RequestConstants.ERROR_CODE_ATTRIBUTE))
        .isEqualTo("KUSS_IDEMPOTENCY_KEY_MISSING");
  }

  @RestController
  static class StubController {

    @GetMapping("/stub/codes/{code}")
    String code(@PathVariable ErrorCode code) {
      throw new SchedulerException(code, "Test detail.");
    }

    @PostMapping("/stub/booking")
    String booking(@RequestHeader(HeaderConstants.IDEMPOTENCY_KEY) String key) {
      return key;
    }

    @PostMapping("/stub/validated")
    String validated(@Valid @RequestBody StubDto dto) {
      return "ok";
    }

    @GetMapping("/stub/number")
    String number(@RequestParam int n) {
      return "ok";
    }

    @GetMapping("/stub/constraint")
    String constraint() {
      throw new ConstraintViolationException("bad", Set.of());
    }

    @GetMapping("/stub/overload")
    String overload(HttpServletRequest request) throws Exception {
      throw (Exception) request.getAttribute("overload");
    }

    @GetMapping("/stub/boom")
    String boom() {
      throw new IllegalStateException("SELECT * FROM secret");
    }
  }

  record StubDto(@NotNull UUID id) {}
}
