CREATE
    EXTENSION IF NOT EXISTS btree_gist;

CREATE
    TABLE
        dealership(
            id uuid PRIMARY KEY,
            name VARCHAR(120) NOT NULL,
            timezone VARCHAR(64) NOT NULL,
            open_time TIME NOT NULL,
            close_time TIME NOT NULL,
            CONSTRAINT chk_dealership_hours CHECK(
                open_time < close_time
            ),
            CONSTRAINT chk_dealership_half_hour_grid CHECK(
                EXTRACT(
                    MINUTE
                FROM
                    open_time
                ) IN(
                    0,
                    30
                )
                AND EXTRACT(
                    MINUTE
                FROM
                    close_time
                ) IN(
                    0,
                    30
                )
            )
        );

CREATE
    TABLE
        service_type(
            code VARCHAR(40) PRIMARY KEY,
            display_name VARCHAR(120) NOT NULL,
            duration_minutes INT NOT NULL,
            active BOOLEAN NOT NULL,
            CONSTRAINT chk_service_type_duration CHECK(
                duration_minutes > 0
                AND duration_minutes % 30 = 0
            )
        );

CREATE
    TABLE
        service_bay(
            id uuid PRIMARY KEY,
            dealership_id uuid NOT NULL REFERENCES dealership(id),
            bay_code VARCHAR(16) NOT NULL,
            active BOOLEAN NOT NULL,
            CONSTRAINT uq_service_bay_code UNIQUE(
                dealership_id,
                bay_code
            )
        );

CREATE
    TABLE
        technician(
            id uuid PRIMARY KEY,
            dealership_id uuid NOT NULL REFERENCES dealership(id),
            full_name VARCHAR(120) NOT NULL,
            active BOOLEAN NOT NULL
        );

CREATE
    TABLE
        technician_skill(
            technician_id uuid NOT NULL REFERENCES technician(id),
            service_type_code VARCHAR(40) NOT NULL REFERENCES service_type(code),
            PRIMARY KEY(
                technician_id,
                service_type_code
            )
        );

CREATE
    TABLE
        customer(
            id uuid PRIMARY KEY,
            full_name VARCHAR(120) NOT NULL,
            email VARCHAR(254) NOT NULL UNIQUE,
            phone VARCHAR(32)
        );

CREATE
    TABLE
        vehicle(
            id uuid PRIMARY KEY,
            customer_id uuid NOT NULL REFERENCES customer(id),
            vin VARCHAR(17) NOT NULL UNIQUE,
            license_plate VARCHAR(16) NOT NULL,
            make VARCHAR(64) NOT NULL,
            model VARCHAR(64) NOT NULL,
            model_year INT NOT NULL
        );

CREATE
    TABLE
        appointment(
            id uuid PRIMARY KEY,
            dealership_id uuid NOT NULL REFERENCES dealership(id),
            customer_id uuid NOT NULL REFERENCES customer(id),
            vehicle_id uuid NOT NULL REFERENCES vehicle(id),
            service_bay_id uuid NOT NULL REFERENCES service_bay(id),
            technician_id uuid NOT NULL REFERENCES technician(id),
            service_type_code VARCHAR(40) NOT NULL REFERENCES service_type(code),
            start_time timestamptz NOT NULL,
            end_time timestamptz NOT NULL,
            status VARCHAR(16) NOT NULL,
            created_at timestamptz NOT NULL DEFAULT now(),
            cancelled_at timestamptz,
            CONSTRAINT chk_appointment_time_order CHECK(
                end_time > start_time
            ),
            CONSTRAINT chk_appointment_status CHECK(
                status IN(
                    'CONFIRMED',
                    'CANCELLED'
                )
            ),
            CONSTRAINT chk_appointment_cancelled_at CHECK(
                (
                    status = 'CANCELLED'
                )=(
                    cancelled_at IS NOT NULL
                )
            ),
            CONSTRAINT excl_appointment_bay_overlap EXCLUDE
                USING gist(
                service_bay_id WITH =,
                tstzrange(
                    start_time,
                    end_time,
                    '[)'
                ) WITH &&
            )
        WHERE
            (
                status = 'CONFIRMED'
            ),
            CONSTRAINT excl_appointment_tech_overlap EXCLUDE
                USING gist(
                technician_id WITH =,
                tstzrange(
                    start_time,
                    end_time,
                    '[)'
                ) WITH &&
            )
        WHERE
            (
                status = 'CONFIRMED'
            ),
            CONSTRAINT excl_appointment_vehicle_overlap EXCLUDE
                USING gist(
                vehicle_id WITH =,
                tstzrange(
                    start_time,
                    end_time,
                    '[)'
                ) WITH &&
            )
        WHERE
            (
                status = 'CONFIRMED'
            )
        );

CREATE
    INDEX idx_appointment_dealership_window ON
    appointment(
        dealership_id,
        start_time,
        end_time
    )
WHERE
    status = 'CONFIRMED';

CREATE
    TABLE
        idempotency_key(
            idempotency_key VARCHAR(128) PRIMARY KEY,
            request_hash VARCHAR(64) NOT NULL,
            response_status INT,
            response_body jsonb,
            created_at timestamptz NOT NULL DEFAULT now()
        );

CREATE
    TABLE
        request_audit(
            id uuid NOT NULL,
            occurred_at timestamptz NOT NULL,
            request_id VARCHAR(64) NOT NULL,
            http_method VARCHAR(10) NOT NULL,
            PATH VARCHAR(255) NOT NULL,
            http_status INT NOT NULL,
            error_code VARCHAR(64),
            duration_ms INT NOT NULL,
            request_payload jsonb,
            PRIMARY KEY(
                id,
                occurred_at
            )
        ) PARTITION BY RANGE(occurred_at);

CREATE
    TABLE
        request_audit_default PARTITION OF request_audit DEFAULT;

CREATE
    INDEX idx_request_audit_occurred_brin ON
    request_audit
        USING brin(occurred_at);

CREATE
    INDEX idx_request_audit_request_id ON
    request_audit(request_id);
