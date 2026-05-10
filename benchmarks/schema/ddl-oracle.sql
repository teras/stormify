BEGIN EXECUTE IMMEDIATE 'DROP TABLE bench_child'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
BEGIN EXECUTE IMMEDIATE 'DROP TABLE bench_parent'; EXCEPTION WHEN OTHERS THEN NULL; END;
/

CREATE TABLE bench_parent (
  id NUMBER(10) PRIMARY KEY,
  name VARCHAR2(100) NOT NULL,
  created_at NUMBER(19) NOT NULL
)
/

CREATE TABLE bench_child (
  id NUMBER(10) PRIMARY KEY,
  parent_id NUMBER(10) NOT NULL,
  status VARCHAR2(20) NOT NULL,
  value BINARY_DOUBLE NOT NULL,
  payload VARCHAR2(200) NOT NULL,
  created_at NUMBER(19) NOT NULL,
  CONSTRAINT fk_child_parent FOREIGN KEY (parent_id) REFERENCES bench_parent(id)
)
/
CREATE INDEX idx_child_parent ON bench_child(parent_id)
/
CREATE INDEX idx_child_status ON bench_child(status)
/

BEGIN EXECUTE IMMEDIATE 'DROP TABLE bench_warmup'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
CREATE TABLE bench_warmup (
  id NUMBER(10) PRIMARY KEY,
  payload VARCHAR2(50) NOT NULL
)
/
INSERT ALL
  INTO bench_warmup (id, payload) VALUES (1, 'a')
  INTO bench_warmup (id, payload) VALUES (2, 'b')
  INTO bench_warmup (id, payload) VALUES (3, 'c')
  INTO bench_warmup (id, payload) VALUES (4, 'd')
  INTO bench_warmup (id, payload) VALUES (5, 'e')
  INTO bench_warmup (id, payload) VALUES (6, 'f')
  INTO bench_warmup (id, payload) VALUES (7, 'g')
  INTO bench_warmup (id, payload) VALUES (8, 'h')
  INTO bench_warmup (id, payload) VALUES (9, 'i')
  INTO bench_warmup (id, payload) VALUES (10, 'j')
SELECT * FROM dual
/
