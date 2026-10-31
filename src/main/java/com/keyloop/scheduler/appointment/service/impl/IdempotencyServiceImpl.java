package com.keyloop.scheduler.appointment.service.impl;

import com.keyloop.scheduler.appointment.dto.AppointmentDto;
import com.keyloop.scheduler.appointment.dto.AppointmentResultDto;
import com.keyloop.scheduler.appointment.entity.IdempotencyKeyEntity;
import com.keyloop.scheduler.appointment.repository.IdempotencyKeyRepository;
import com.keyloop.scheduler.appointment.service.IdempotencyService;
import com.keyloop.scheduler.common.constants.ErrorMessageConstants;
import com.keyloop.scheduler.common.constants.RequestConstants;
import com.keyloop.scheduler.common.error.ErrorCode;
import com.keyloop.scheduler.common.error.SchedulerException;
import com.keyloop.scheduler.common.util.SqlErrorUtils;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
class IdempotencyServiceImpl implements IdempotencyService {

  private static final String DUPLICATE_KEY_STATE = "23505";

  private final IdempotencyKeyRepository repository;
  private final JsonMapper jsonMapper;
  private final Clock clock;

  /**
   * Claims the key inside the caller's transaction, so the claim commits or rolls back together
   * with the booking. A stored response for the same canonical request is replayed; the same key
   * with a different request is rejected; a claim held by another transaction is reported as in
   * flight. A key older than 24 hours is treated as absent and replaced, even before the daily
   * purge removes it.
   */
  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<AppointmentResultDto> claim(String key, String canonicalRequest) {
    String requestHash = sha256Hex(canonicalRequest);
    Optional<IdempotencyKeyEntity> existing = findLive(key);
    if (existing.isEmpty()) {
      insertClaim(key, requestHash);
      return Optional.empty();
    }
    return Optional.of(replay(existing.get(), requestHash));
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void complete(String key, AppointmentResultDto result) {
    IdempotencyKeyEntity entity = repository.findById(key).orElseThrow();
    entity.setResponseStatus(result.httpStatus());
    entity.setResponseBody(jsonMapper.writeValueAsString(result.appointment()));
    repository.saveAndFlush(entity);
  }

  private Optional<IdempotencyKeyEntity> findLive(String key) {
    Instant cutoff = clock.instant().minus(RequestConstants.IDEMPOTENCY_KEY_TTL);
    Optional<IdempotencyKeyEntity> existing = repository.findById(key);
    if (existing.isPresent() && existing.get().getCreatedAt().isBefore(cutoff)) {
      repository.deleteExpired(key, cutoff);
      return Optional.empty();
    }
    return existing;
  }

  private void insertClaim(String key, String requestHash) {
    Instant createdAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
    try {
      repository.saveAndFlush(
          IdempotencyKeyEntity.builder()
              .key(key)
              .requestHash(requestHash)
              .createdAt(createdAt)
              .build());
    } catch (DataAccessException ex) {
      if (!DUPLICATE_KEY_STATE.equals(SqlErrorUtils.sqlState(ex))) {
        throw ex;
      }
      throw inFlight();
    }
  }

  private AppointmentResultDto replay(IdempotencyKeyEntity stored, String requestHash) {
    if (!stored.getRequestHash().equals(requestHash)) {
      throw new SchedulerException(
          ErrorCode.KUSS_IDEMPOTENCY_KEY_MISMATCH, ErrorMessageConstants.IDEMPOTENCY_KEY_MISMATCH);
    }
    if (stored.getResponseStatus() == null) {
      throw inFlight();
    }
    AppointmentDto appointment =
        jsonMapper.readValue(stored.getResponseBody(), AppointmentDto.class);
    return AppointmentResultDto.builder()
        .httpStatus(stored.getResponseStatus())
        .appointment(appointment)
        .build();
  }

  private static SchedulerException inFlight() {
    return new SchedulerException(
        ErrorCode.KUSS_IDEMPOTENCY_IN_FLIGHT, ErrorMessageConstants.IDEMPOTENCY_IN_FLIGHT);
  }

  private static String sha256Hex(String canonicalRequest) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(canonicalRequest.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }
}
