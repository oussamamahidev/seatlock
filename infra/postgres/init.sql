
CREATE USER inventory_user
WITH PASSWORD 'dev_only_inventory_password';

CREATE USER booking_user
WITH PASSWORD 'dev_only_booking_password';

CREATE DATABASE inventory_db
OWNER inventory_user;

CREATE DATABASE booking_db
OWNER booking_user;
