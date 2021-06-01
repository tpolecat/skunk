# Identifiers

Please visit PostgreSQL documentation to familiarize with `RETURNING` syntax: [Returning Data From Modified Rows](https://www.postgresql.org/docs/9.5/dml-returning.html)

## Returning identifiers

```
val savePet: Query[Pet, Int] =
  sql"""
    INSERT INTO pets (
    VALUES ${petCodec.values}
    RETURNING id;
  """.query(int4)
```

## Returning entities

It's reasonable to return just an identifier in case there are no other database-managed columns.
Let's assume we have five columns: `id`, `name`, `colour`, `created_at`, `created_by` where `id`, `created_at`, `created_by` are not part of initial `NewPet` entity.
That means you need to query whole entity with RETURNING expression.
```
val savePet: Query[NewPet, PersistedPet] =
  sql"""
    INSERT INTO #$PETS_TABLE_NAME (name, colour)
    VALUES ${newPetCodec.values}
    RETURNING id, name, colour, created_at, created_by;
  """.query(persistedPetCodec)
```
Please note that you might want to write down all column names (not just `*`) to decouple entity and database representation.