IF OBJECT_ID('bench_child', 'U') IS NOT NULL DROP TABLE bench_child;
IF OBJECT_ID('bench_parent', 'U') IS NOT NULL DROP TABLE bench_parent;

CREATE TABLE bench_parent (
  id INT PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  created_at BIGINT NOT NULL
);

CREATE TABLE bench_child (
  id INT PRIMARY KEY,
  parent_id INT NOT NULL FOREIGN KEY REFERENCES bench_parent(id),
  status VARCHAR(20) NOT NULL,
  value FLOAT NOT NULL,
  payload VARCHAR(200) NOT NULL,
  created_at BIGINT NOT NULL
);
CREATE INDEX idx_child_parent ON bench_child(parent_id);
CREATE INDEX idx_child_status ON bench_child(status);

IF OBJECT_ID('bench_warmup', 'U') IS NOT NULL DROP TABLE bench_warmup;
CREATE TABLE bench_warmup (
  id INT PRIMARY KEY,
  payload VARCHAR(50) NOT NULL
);
INSERT INTO bench_warmup (id, payload) VALUES (1, 'a'),(2, 'b'),(3, 'c'),(4, 'd'),(5, 'e'),(6, 'f'),(7, 'g'),(8, 'h'),(9, 'i'),(10, 'j');
