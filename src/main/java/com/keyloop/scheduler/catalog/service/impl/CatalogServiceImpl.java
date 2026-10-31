package com.keyloop.scheduler.catalog.service.impl;

import com.keyloop.scheduler.catalog.dto.CustomerDto;
import com.keyloop.scheduler.catalog.dto.DealershipDetailDto;
import com.keyloop.scheduler.catalog.dto.DealershipDto;
import com.keyloop.scheduler.catalog.dto.ServiceBayDto;
import com.keyloop.scheduler.catalog.dto.ServiceTypeDto;
import com.keyloop.scheduler.catalog.dto.TechnicianDto;
import com.keyloop.scheduler.catalog.dto.VehicleDto;
import com.keyloop.scheduler.catalog.entity.DealershipEntity;
import com.keyloop.scheduler.catalog.entity.VehicleEntity;
import com.keyloop.scheduler.catalog.mapper.CatalogMapper;
import com.keyloop.scheduler.catalog.repository.CustomerRepository;
import com.keyloop.scheduler.catalog.repository.DealershipRepository;
import com.keyloop.scheduler.catalog.repository.ServiceBayRepository;
import com.keyloop.scheduler.catalog.repository.ServiceTypeRepository;
import com.keyloop.scheduler.catalog.repository.TechnicianRepository;
import com.keyloop.scheduler.catalog.repository.VehicleRepository;
import com.keyloop.scheduler.catalog.service.CatalogService;
import com.keyloop.scheduler.common.constants.ErrorMessageConstants;
import com.keyloop.scheduler.common.constants.RequestConstants;
import com.keyloop.scheduler.common.error.ErrorCode;
import com.keyloop.scheduler.common.error.SchedulerException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class CatalogServiceImpl implements CatalogService {

  private final DealershipRepository dealershipRepository;
  private final ServiceBayRepository serviceBayRepository;
  private final TechnicianRepository technicianRepository;
  private final ServiceTypeRepository serviceTypeRepository;
  private final CustomerRepository customerRepository;
  private final VehicleRepository vehicleRepository;
  private final CatalogMapper catalogMapper;

  @Override
  @Transactional(readOnly = true, timeout = RequestConstants.TRANSACTION_TIMEOUT_SECONDS)
  public List<DealershipDto> findDealerships() {
    return dealershipRepository.findAll().stream().map(catalogMapper::toDealership).toList();
  }

  @Override
  @Transactional(readOnly = true, timeout = RequestConstants.TRANSACTION_TIMEOUT_SECONDS)
  public DealershipDetailDto findDealership(UUID dealershipId) {
    DealershipEntity dealership =
        dealershipRepository
            .findById(dealershipId)
            .orElseThrow(() ->
                new SchedulerException(
                    ErrorCode.KUSS_RESOURCE_NOT_FOUND, ErrorMessageConstants.DEALERSHIP_NOT_FOUND));
    List<ServiceBayDto> bays =
        serviceBayRepository.findByDealershipIdOrderByBayCode(dealershipId).stream()
            .map(catalogMapper::toBay)
            .toList();
    List<TechnicianDto> technicians =
        technicianRepository.findByDealershipIdOrderByFullName(dealershipId).stream()
            .map(catalogMapper::toTechnician)
            .toList();
    return catalogMapper.toDealershipDetail(dealership, bays, technicians);
  }

  @Override
  @Transactional(readOnly = true, timeout = RequestConstants.TRANSACTION_TIMEOUT_SECONDS)
  public List<ServiceTypeDto> findServiceTypes() {
    return serviceTypeRepository.findByActiveTrueOrderByCode().stream()
        .map(catalogMapper::toServiceType)
        .toList();
  }

  @Override
  @Transactional(readOnly = true, timeout = RequestConstants.TRANSACTION_TIMEOUT_SECONDS)
  public List<CustomerDto> findCustomers() {
    Map<UUID, List<VehicleDto>> vehiclesByCustomer =
        vehicleRepository.findAll().stream()
            .collect(
                Collectors.groupingBy(
                    VehicleEntity::getCustomerId,
                    Collectors.mapping(catalogMapper::toVehicle, Collectors.toList())));
    return customerRepository.findAll().stream()
        .map(customer ->
            catalogMapper.toCustomer(
                customer, vehiclesByCustomer.getOrDefault(customer.getId(), List.of())))
        .toList();
  }
}
