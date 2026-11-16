-- =====================================================================
-- Money: DECIMAL(19,2)   (scale 2, exact). Tax rates: DECIMAL(9,4).
-- Timestamps: TIMESTAMP WITH TIME ZONE (UTC offset preserved).
-- =====================================================================

-- ---------------------------------------------------------------------
-- Transactions (header). Type-specific data lives in `metadata` (JSON text,
-- validated per type on write); identity, money, taxes and fees are real
-- columns / child tables.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS transactions (
    id           VARCHAR(40)  NOT NULL,
    external_id  VARCHAR(255) NOT NULL,
    type         VARCHAR(20)  NOT NULL,
    currency     VARCHAR(3)   NOT NULL,
    occurred_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    total        DECIMAL(19,2) NOT NULL,
    metadata     CLOB         NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_transactions PRIMARY KEY (id),
    -- Duplicate identity is enforced HERE, by the database, not by application code.
    CONSTRAINT uq_transactions_external_id UNIQUE (external_id),
    CONSTRAINT ck_transactions_type CHECK (type IN ('flight', 'hotel', 'rail', 'navan_fee')),
    CONSTRAINT ck_transactions_total CHECK (total >= 0)
);

-- Keyset-friendly ordering for GET /transactions (newest first).
CREATE INDEX IF NOT EXISTS idx_transactions_created ON transactions (created_at, id);

CREATE TABLE IF NOT EXISTS transaction_tax_lines (
    transaction_id VARCHAR(40)   NOT NULL,
    line_no        INT           NOT NULL,
    name           VARCHAR(100)  NOT NULL,
    rate           DECIMAL(9,4),
    amount         DECIMAL(19,2) NOT NULL,
    CONSTRAINT pk_transaction_tax_lines PRIMARY KEY (transaction_id, line_no),
    CONSTRAINT fk_tax_lines_txn FOREIGN KEY (transaction_id)
        REFERENCES transactions (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS transaction_fee_lines (
    transaction_id VARCHAR(40)   NOT NULL,
    line_no        INT           NOT NULL,
    name           VARCHAR(100)  NOT NULL,
    amount         DECIMAL(19,2) NOT NULL,
    CONSTRAINT pk_transaction_fee_lines PRIMARY KEY (transaction_id, line_no),
    CONSTRAINT fk_fee_lines_txn FOREIGN KEY (transaction_id)
        REFERENCES transactions (id) ON DELETE CASCADE
);

-- ---------------------------------------------------------------------
-- Invoices: a FROZEN COPY of the transactions at invoice time.
-- invoice_lines stores its own copy of type/total/metadata and its own
-- tax/fee line copies. The only reference back to `transactions` is
-- invoice_lines.transaction_id, used for "is this transaction invoiced?"
-- and as a RESTRICT foreign key (the DB itself refuses to delete a
-- transaction that is on an invoice).
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS invoices (
    id          VARCHAR(40)   NOT NULL,
    currency    VARCHAR(3)    NOT NULL,
    total       DECIMAL(19,2) NOT NULL,
    tax_total   DECIMAL(19,2) NOT NULL,
    fee_total   DECIMAL(19,2) NOT NULL,
    line_count  INT           NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_invoices PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_invoices_created ON invoices (created_at, id);

CREATE TABLE IF NOT EXISTS invoice_lines (
    invoice_id     VARCHAR(40)   NOT NULL,
    transaction_id VARCHAR(40)   NOT NULL,
    line_no        INT           NOT NULL,
    external_id    VARCHAR(255)  NOT NULL,
    type           VARCHAR(20)   NOT NULL,
    currency       VARCHAR(3)    NOT NULL,
    occurred_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    total          DECIMAL(19,2) NOT NULL,
    metadata       CLOB          NOT NULL,
    -- the same transaction cannot be listed twice on one invoice
    CONSTRAINT pk_invoice_lines PRIMARY KEY (invoice_id, transaction_id),
    CONSTRAINT fk_invoice_lines_invoice FOREIGN KEY (invoice_id)
        REFERENCES invoices (id) ON DELETE CASCADE,
    CONSTRAINT fk_invoice_lines_txn FOREIGN KEY (transaction_id)
        REFERENCES transactions (id)
);

-- "Is this transaction on any invoice?" must be a cheap index lookup.
CREATE INDEX IF NOT EXISTS idx_invoice_lines_txn ON invoice_lines (transaction_id);

CREATE TABLE IF NOT EXISTS invoice_line_tax_lines (
    invoice_id     VARCHAR(40)   NOT NULL,
    transaction_id VARCHAR(40)   NOT NULL,
    line_no        INT           NOT NULL,
    name           VARCHAR(100)  NOT NULL,
    rate           DECIMAL(9,4),
    amount         DECIMAL(19,2) NOT NULL,
    CONSTRAINT pk_invoice_line_tax_lines PRIMARY KEY (invoice_id, transaction_id, line_no),
    CONSTRAINT fk_invoice_line_tax_lines FOREIGN KEY (invoice_id, transaction_id)
        REFERENCES invoice_lines (invoice_id, transaction_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS invoice_line_fee_lines (
    invoice_id     VARCHAR(40)   NOT NULL,
    transaction_id VARCHAR(40)   NOT NULL,
    line_no        INT           NOT NULL,
    name           VARCHAR(100)  NOT NULL,
    amount         DECIMAL(19,2) NOT NULL,
    CONSTRAINT pk_invoice_line_fee_lines PRIMARY KEY (invoice_id, transaction_id, line_no),
    CONSTRAINT fk_invoice_line_fee_lines FOREIGN KEY (invoice_id, transaction_id)
        REFERENCES invoice_lines (invoice_id, transaction_id) ON DELETE CASCADE
);

-- Invoice-level rollups, stored (also part of the frozen snapshot).
CREATE TABLE IF NOT EXISTS invoice_tax_rollup (
    invoice_id VARCHAR(40)   NOT NULL,
    line_no    INT           NOT NULL,
    name       VARCHAR(100)  NOT NULL,
    rate       DECIMAL(9,4),
    amount     DECIMAL(19,2) NOT NULL,
    CONSTRAINT pk_invoice_tax_rollup PRIMARY KEY (invoice_id, line_no),
    CONSTRAINT fk_invoice_tax_rollup FOREIGN KEY (invoice_id)
        REFERENCES invoices (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS invoice_fee_rollup (
    invoice_id VARCHAR(40)   NOT NULL,
    line_no    INT           NOT NULL,
    name       VARCHAR(100)  NOT NULL,
    amount     DECIMAL(19,2) NOT NULL,
    CONSTRAINT pk_invoice_fee_rollup PRIMARY KEY (invoice_id, line_no),
    CONSTRAINT fk_invoice_fee_rollup FOREIGN KEY (invoice_id)
        REFERENCES invoices (id) ON DELETE CASCADE
);
