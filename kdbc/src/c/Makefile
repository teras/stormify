CC       = gcc
CFLAGS   = -Wall -Wextra -std=c11 -fPIC -Iinclude -g -O2

# Windows doesn't need -ldl (uses LoadLibrary from kernel32)
ifeq ($(OS),Windows_NT)
  LDFLAGS =
else
  LDFLAGS = -ldl
endif

SRC_CORE    = kdbc_core.c
SRC_DRIVERS = drivers/driver_sqlite.c \
              drivers/driver_postgres.c \
              drivers/driver_mariadb.c \
              drivers/driver_oracle.c \
              drivers/driver_freetds.c

OBJ_CORE    = $(SRC_CORE:.c=.o)
OBJ_DRIVERS = $(SRC_DRIVERS:.c=.o)
OBJECTS     = $(OBJ_CORE) $(OBJ_DRIVERS)

LIB_STATIC  = libkdbc.a
LIB_SHARED  = libkdbc.so

TEST_SRC    = test/test_kdbc.c
TEST_BIN    = test/test_kdbc

.PHONY: all clean test lib

all: lib

lib: $(LIB_STATIC) $(LIB_SHARED)

$(LIB_STATIC): $(OBJECTS)
	ar rcs $@ $^

$(LIB_SHARED): $(OBJECTS)
	$(CC) -shared -o $@ $^ $(LDFLAGS)

%.o: %.c
	$(CC) $(CFLAGS) -c -o $@ $<

test: $(TEST_BIN)
	./$(TEST_BIN)

$(TEST_BIN): $(TEST_SRC) $(LIB_STATIC)
	$(CC) $(CFLAGS) -o $@ $< $(LIB_STATIC) $(LDFLAGS) -lm

clean:
	rm -f $(OBJECTS) $(LIB_STATIC) $(LIB_SHARED) $(TEST_BIN)
