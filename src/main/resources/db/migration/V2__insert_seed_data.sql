INSERT
    INTO
        dealership(
            id,
            name,
            timezone,
            open_time,
            close_time
        )
    VALUES(
        '00000000-0000-0000-0001-000000000001',
        'London Central',
        'Europe/London',
        '08:00',
        '18:00'
    ),
    (
        '00000000-0000-0000-0001-000000000002',
        'New York Midtown',
        'America/New_York',
        '08:00',
        '17:00'
    );

INSERT
    INTO
        service_type(
            code,
            display_name,
            duration_minutes,
            active
        )
    VALUES(
        'OIL_CHANGE',
        'Oil change',
        30,
        TRUE
    ),
    (
        'TIRE_SERVICE',
        'Tire service',
        60,
        TRUE
    ),
    (
        'BRAKE_REPAIR',
        'Brake repair',
        90,
        TRUE
    ),
    (
        'ENGINE_DIAGNOSTIC',
        'Engine diagnostic',
        120,
        TRUE
    );

INSERT
    INTO
        service_bay(
            id,
            dealership_id,
            bay_code,
            active
        )
    VALUES(
        '00000000-0000-0000-0002-000000000101',
        '00000000-0000-0000-0001-000000000001',
        'BAY-01',
        TRUE
    ),
    (
        '00000000-0000-0000-0002-000000000102',
        '00000000-0000-0000-0001-000000000001',
        'BAY-02',
        TRUE
    ),
    (
        '00000000-0000-0000-0002-000000000103',
        '00000000-0000-0000-0001-000000000001',
        'BAY-03',
        TRUE
    ),
    (
        '00000000-0000-0000-0002-000000000104',
        '00000000-0000-0000-0001-000000000001',
        'BAY-04',
        FALSE
    ),
    (
        '00000000-0000-0000-0002-000000000201',
        '00000000-0000-0000-0001-000000000002',
        'BAY-01',
        TRUE
    ),
    (
        '00000000-0000-0000-0002-000000000202',
        '00000000-0000-0000-0001-000000000002',
        'BAY-02',
        TRUE
    );

INSERT
    INTO
        technician(
            id,
            dealership_id,
            full_name,
            active
        )
    VALUES(
        '00000000-0000-0000-0003-000000000101',
        '00000000-0000-0000-0001-000000000001',
        'London Tech 01',
        TRUE
    ),
    (
        '00000000-0000-0000-0003-000000000102',
        '00000000-0000-0000-0001-000000000001',
        'London Tech 02',
        TRUE
    ),
    (
        '00000000-0000-0000-0003-000000000103',
        '00000000-0000-0000-0001-000000000001',
        'London Tech 03',
        TRUE
    ),
    (
        '00000000-0000-0000-0003-000000000104',
        '00000000-0000-0000-0001-000000000001',
        'London Tech 04',
        TRUE
    ),
    (
        '00000000-0000-0000-0003-000000000105',
        '00000000-0000-0000-0001-000000000001',
        'London Tech 05',
        TRUE
    ),
    (
        '00000000-0000-0000-0003-000000000201',
        '00000000-0000-0000-0001-000000000002',
        'New York Tech 01',
        TRUE
    ),
    (
        '00000000-0000-0000-0003-000000000202',
        '00000000-0000-0000-0001-000000000002',
        'New York Tech 02',
        TRUE
    );

INSERT
    INTO
        technician_skill(
            technician_id,
            service_type_code
        )
    VALUES(
        '00000000-0000-0000-0003-000000000101',
        'OIL_CHANGE'
    ),
    (
        '00000000-0000-0000-0003-000000000101',
        'TIRE_SERVICE'
    ),
    (
        '00000000-0000-0000-0003-000000000101',
        'BRAKE_REPAIR'
    ),
    (
        '00000000-0000-0000-0003-000000000102',
        'OIL_CHANGE'
    ),
    (
        '00000000-0000-0000-0003-000000000102',
        'TIRE_SERVICE'
    ),
    (
        '00000000-0000-0000-0003-000000000103',
        'OIL_CHANGE'
    ),
    (
        '00000000-0000-0000-0003-000000000103',
        'BRAKE_REPAIR'
    ),
    (
        '00000000-0000-0000-0003-000000000104',
        'OIL_CHANGE'
    ),
    (
        '00000000-0000-0000-0003-000000000105',
        'ENGINE_DIAGNOSTIC'
    ),
    (
        '00000000-0000-0000-0003-000000000201',
        'OIL_CHANGE'
    ),
    (
        '00000000-0000-0000-0003-000000000201',
        'TIRE_SERVICE'
    ),
    (
        '00000000-0000-0000-0003-000000000201',
        'BRAKE_REPAIR'
    ),
    (
        '00000000-0000-0000-0003-000000000201',
        'ENGINE_DIAGNOSTIC'
    ),
    (
        '00000000-0000-0000-0003-000000000202',
        'OIL_CHANGE'
    );

INSERT
    INTO
        customer(
            id,
            full_name,
            email,
            phone
        ) SELECT
            (
                '00000000-0000-0000-0004-' || lpad(
                    n ::text,
                    12,
                    '0'
                )
            )::uuid,
            'Test Customer ' || n,
            'customer' || n || '@example.test',
            NULL
        FROM
            generate_series(
                1,
                3
            ) AS n;

INSERT
    INTO
        vehicle(
            id,
            customer_id,
            vin,
            license_plate,
            make,
            model,
            model_year
        ) SELECT
            (
                '00000000-0000-0000-0005-' || lpad(
                    n ::text,
                    12,
                    '0'
                )
            )::uuid,
            (
                '00000000-0000-0000-0004-' || lpad(
                    (
                        (
                            n - 1
                        )% 3 + 1
                    )::text,
                    12,
                    '0'
                )
            )::uuid,
            'KLTEST' || lpad(
                n ::text,
                11,
                '0'
            ),
            'KL-' || lpad(
                n ::text,
                4,
                '0'
            ),
            'Toyota',
            'Corolla',
            2020
        FROM
            generate_series(
                1,
                24
            ) AS n;
