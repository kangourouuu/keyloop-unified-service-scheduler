package com.keyloop.scheduler.catalog.service;

import com.keyloop.scheduler.catalog.dto.CustomerDto;
import com.keyloop.scheduler.catalog.dto.DealershipDetailDto;
import com.keyloop.scheduler.catalog.dto.DealershipDto;
import com.keyloop.scheduler.catalog.dto.ServiceTypeDto;
import com.keyloop.scheduler.catalog.entity.DealershipEntity;
import com.keyloop.scheduler.catalog.entity.ServiceTypeEntity;
import com.keyloop.scheduler.catalog.entity.VehicleEntity;
import java.util.List;
import java.util.UUID;

public interface CatalogService {

  /** Returns the dealership or fails with KUSS_RESOURCE_NOT_FOUND. */
  DealershipEntity requireDealership(UUID dealershipId);

  /** Returns the vehicle or fails with KUSS_RESOURCE_NOT_FOUND. */
  VehicleEntity requireVehicle(UUID vehicleId);

  /** Returns the active service type or fails with KUSS_RESOURCE_NOT_FOUND. */
  ServiceTypeEntity requireServiceType(String serviceTypeCode);

  List<DealershipDto> findDealerships();

  DealershipDetailDto findDealership(UUID dealershipId);

  List<ServiceTypeDto> findServiceTypes();

  List<CustomerDto> findCustomers();
}
