CREATE TABLE IF NOT EXISTS jcr_items (
	item_id VARCHAR NOT NULL,
	item_name VARCHAR NOT NULL,
	item_path VARCHAR,
	parent_item_id VARCHAR,
	is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
	PRIMARY KEY (item_id)
);
CREATE INDEX IF NOT EXISTS jcr_items_index1 ON jcr_items (parent_item_id, item_name);
CREATE INDEX IF NOT EXISTS jcr_items_index2 ON jcr_items (item_path);
CREATE INDEX IF NOT EXISTS jcr_items_index3 ON jcr_items (is_deleted);

CREATE TABLE IF NOT EXISTS jcr_properties (
	item_id VARCHAR NOT NULL,
	item_name VARCHAR NOT NULL,
	parent_item_id VARCHAR NOT NULL,
	property_type INTEGER NOT NULL,
	property_value VARCHAR ARRAY NOT NULL,
	is_multiple BOOLEAN NOT NULL,
	is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
	PRIMARY KEY (item_id)
);
CREATE INDEX IF NOT EXISTS jcr_properties_index1 ON jcr_properties (parent_item_id, item_name);
CREATE INDEX IF NOT EXISTS jcr_properties_index2 ON jcr_properties (property_type, property_value);
CREATE INDEX IF NOT EXISTS jcr_properties_index3 ON jcr_properties (is_deleted);

CREATE TABLE IF NOT EXISTS jcr_files (
	file_id VARCHAR NOT NULL,
	file_size BIGINT NOT NULL,
	is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
	PRIMARY KEY (file_id)
);
CREATE INDEX IF NOT EXISTS jcr_files_index1 ON jcr_files (is_deleted);

CREATE TABLE IF NOT EXISTS jcr_locks (
	item_id VARCHAR NOT NULL,
	is_deep BOOLEAN NOT NULL,
	session_id VARCHAR,
	timeout_hint BIGINT NOT NULL,
	owner_info VARCHAR,
	principal_name VARCHAR NOT NULL,
	lock_created BIGINT NOT NULL,
	lock_token VARCHAR NOT NULL,
	PRIMARY KEY (item_id)
);
CREATE INDEX IF NOT EXISTS jcr_locks_index1 ON jcr_locks (session_id);
CREATE INDEX IF NOT EXISTS jcr_locks_index2 ON jcr_locks (principal_name);

CREATE TABLE IF NOT EXISTS jcr_aces (
	item_id VARCHAR NOT NULL,
	row_no INTEGER NOT NULL,
	principal_name VARCHAR NOT NULL,
	is_group BOOLEAN NOT NULL,
	privilege_names VARCHAR ARRAY NOT NULL,
	is_allow BOOLEAN NOT NULL,
	PRIMARY KEY (item_id, row_no)
);

CREATE TABLE IF NOT EXISTS jcr_namespaces (
	namespace_prefix VARCHAR NOT NULL,
	namespace_uri VARCHAR NOT NULL,
	PRIMARY KEY (namespace_prefix)
);
CREATE INDEX IF NOT EXISTS jcr_namespaces_index1 ON jcr_namespaces (namespace_uri);

CREATE TABLE IF NOT EXISTS jcr_journal (
	journal_id VARCHAR NOT NULL,
	transaction_id VARCHAR NOT NULL,
	session_id VARCHAR NOT NULL,
	event_occurred BIGINT NOT NULL,
	event_type INTEGER NOT NULL,
	item_id VARCHAR NOT NULL,
	item_path VARCHAR NOT NULL,
	primary_type VARCHAR NOT NULL,
	property_name VARCHAR,
	user_id VARCHAR NOT NULL,
	user_data VARCHAR,
	event_info VARCHAR,
	PRIMARY KEY (journal_id)
);
CREATE INDEX IF NOT EXISTS jcr_journal_index1 ON jcr_journal (transaction_id, item_id, event_type);
CREATE INDEX IF NOT EXISTS jcr_journal_index2 ON jcr_journal (transaction_id, item_id, property_name, event_type);
