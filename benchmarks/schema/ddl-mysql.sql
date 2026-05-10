DROP TABLE IF EXISTS bench_child;
DROP TABLE IF EXISTS bench_parent;

CREATE TABLE bench_parent (
  id INT PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  created_at BIGINT NOT NULL
) ENGINE=InnoDB;

CREATE TABLE bench_child (
  id INT PRIMARY KEY,
  parent_id INT NOT NULL,
  status VARCHAR(20) NOT NULL,
  value DOUBLE NOT NULL,
  payload VARCHAR(200) NOT NULL,
  created_at BIGINT NOT NULL,
  CONSTRAINT fk_child_parent FOREIGN KEY (parent_id) REFERENCES bench_parent(id)
) ENGINE=InnoDB;
CREATE INDEX idx_child_parent ON bench_child(parent_id);
CREATE INDEX idx_child_status ON bench_child(status);

DROP TABLE IF EXISTS bench_warmup;
CREATE TABLE bench_warmup (
  id INT PRIMARY KEY,
  payload VARCHAR(50) NOT NULL
) ENGINE=InnoDB;
INSERT INTO bench_warmup (id, payload) VALUES (1, 'a'),(2, 'b'),(3, 'c'),(4, 'd'),(5, 'e'),(6, 'f'),(7, 'g'),(8, 'h'),(9, 'i'),(10, 'j');
