package com.keyloop.scheduler.catalog;

import java.util.List;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
interface CatalogMapper {

  DealershipDto toDealership(DealershipEntity dealership);

  DealershipDetailDto toDealershipDetail(
      DealershipEntity dealership, List<ServiceBayDto> bays, List<TechnicianDto> technicians);

  ServiceBayDto toBay(ServiceBayEntity bay);

  TechnicianDto toTechnician(TechnicianEntity technician);

  ServiceTypeDto toServiceType(ServiceTypeEntity serviceType);

  CustomerDto toCustomer(CustomerEntity customer, List<VehicleDto> vehicles);

  VehicleDto toVehicle(VehicleEntity vehicle);
}
