package com.keyloop.scheduler.catalog.controller;

import com.keyloop.scheduler.catalog.dto.CustomerDto;
import com.keyloop.scheduler.catalog.dto.DealershipDetailDto;
import com.keyloop.scheduler.catalog.dto.DealershipDto;
import com.keyloop.scheduler.catalog.dto.ServiceTypeDto;
import com.keyloop.scheduler.catalog.service.CatalogService;
import com.keyloop.scheduler.common.constants.ApiPathConstants;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
class CatalogController {

  private final CatalogService catalogService;

  @GetMapping(ApiPathConstants.DEALERSHIPS)
  List<DealershipDto> findDealerships() {
    return catalogService.findDealerships();
  }

  @GetMapping(ApiPathConstants.DEALERSHIPS + "/{dealershipId}")
  DealershipDetailDto findDealership(@PathVariable UUID dealershipId) {
    return catalogService.findDealership(dealershipId);
  }

  @GetMapping(ApiPathConstants.SERVICE_TYPES)
  List<ServiceTypeDto> findServiceTypes() {
    return catalogService.findServiceTypes();
  }

  @GetMapping(ApiPathConstants.CUSTOMERS)
  List<CustomerDto> findCustomers() {
    return catalogService.findCustomers();
  }
}
