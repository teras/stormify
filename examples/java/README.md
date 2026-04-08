# Stormify Java Demo

A self-contained Java application demonstrating Stormify ORM with an SQLite database.

## Highlights

This example showcases **mix-and-match annotations**: the `User` entity uses standard **JPA annotations** (`@Id`, `@GeneratedValue`), while the `Task` entity uses **Stormify annotations** (`@DbTable`, `@DbField`). Both work seamlessly in the same project.

Both entities extend `AutoTable` for **lazy-loaded references** — the `Task.user` field is automatically resolved from the `user_id` foreign key column, and its data is loaded on first access via `populate()`.

## What it demonstrates

- **Schema setup** with raw SQL (`executeUpdate`)
- **CRUD operations** — create, findById, findAll, update, delete
- **Entity references** with lazy loading (`AutoTable` + `populate()`)
- **Transactions** with automatic rollback on exception
- **Raw SQL JOIN query** returning `Map<String, Object>` results
- **JPA + Stormify annotation** interoperability

## Run

```bash
mvn compile exec:java
```
