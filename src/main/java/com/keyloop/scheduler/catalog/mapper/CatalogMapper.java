package com.keyloop.scheduler.catalog.mapper;

import com.keyloop.scheduler.catalog.dto.CustomerDto;
import com.keyloop.scheduler.catalog.dto.DealershipDetailDto;
import com.keyloop.scheduler.catalog.dto.DealershipDto;
import com.keyloop.scheduler.catalog.dto.ServiceBayDto;
import com.keyloop.scheduler.catalog.dto.ServiceTypeDto;
import com.keyloop.scheduler.catalog.dto.TechnicianDto;
import com.keyloop.scheduler.catalog.dto.VehicleDto;
import com.keyloop.scheduler.catalog.entity.CustomerEntity;
import com.keyloop.scheduler.catalog.entity.DealershipEntity;
import com.keyloop.scheduler.catalog.entity.ServiceBayEntity;
import com.keyloop.scheduler.catalog.entity.ServiceTypeEntity;
import com.keyloop.scheduler.catalog.entity.TechnicianEntity;
import com.keyloop.scheduler.catalog.entity.VehicleEntity;
import java.util.List;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface CatalogMapper {

  DealershipDto toDealership(DealershipEntity dealership);

  DealershipDetailDto toDealershipDetail(
      DealershipEntity dealership, List<ServiceBayDto> bays, List<TechnicianDto> technicians);

  ServiceBayDto toBay(ServiceBayEntity bay);

  TechnicianDto toTechnician(TechnicianEntity technician);

  ServiceTypeDto toServiceType(ServiceTypeEntity serviceType);

  CustomerDto toCustomer(CustomerEntity customer, List<VehicleDto> vehicles);

  VehicleDto toVehicle(VehicleEntity vehicle);
}
