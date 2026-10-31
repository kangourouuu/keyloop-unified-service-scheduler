package com.keyloop.scheduler.catalog;

import java.util.List;
import java.util.UUID;

public interface CatalogService {

  List<DealershipDto> findDealerships();

  DealershipDetailDto findDealership(UUID dealershipId);

  List<ServiceTypeDto> findServiceTypes();

  List<CustomerDto> findCustomers();
}
