package com.keyloop.scheduler.catalog.controller;

import com.keyloop.scheduler.catalog.dto.CustomerDto;
import com.keyloop.scheduler.catalog.dto.DealershipDetailDto;
import com.keyloop.scheduler.catalog.dto.DealershipDto;
import com.keyloop.scheduler.catalog.dto.ServiceTypeDto;
import com.keyloop.scheduler.catalog.service.CatalogService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
class CatalogController {

  private final CatalogService catalogService;

  @GetMapping("/dealerships")
  List<DealershipDto> findDealerships() {
    return catalogService.findDealerships();
  }

  @GetMapping("/dealerships/{dealershipId}")
  DealershipDetailDto findDealership(@PathVariable UUID dealershipId) {
    return catalogService.findDealership(dealershipId);
  }

  @GetMapping("/service-types")
  List<ServiceTypeDto> findServiceTypes() {
    return catalogService.findServiceTypes();
  }

  @GetMapping("/customers")
  List<CustomerDto> findCustomers() {
    return catalogService.findCustomers();
  }
}
