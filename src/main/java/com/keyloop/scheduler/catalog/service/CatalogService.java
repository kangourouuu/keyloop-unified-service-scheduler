package com.keyloop.scheduler.catalog.service;

import com.keyloop.scheduler.catalog.dto.CustomerDto;
import com.keyloop.scheduler.catalog.dto.DealershipDetailDto;
import com.keyloop.scheduler.catalog.dto.DealershipDto;
import com.keyloop.scheduler.catalog.dto.ServiceTypeDto;
import java.util.List;
import java.util.UUID;

public interface CatalogService {

  List<DealershipDto> findDealerships();

  DealershipDetailDto findDealership(UUID dealershipId);

  List<ServiceTypeDto> findServiceTypes();

  List<CustomerDto> findCustomers();
}
