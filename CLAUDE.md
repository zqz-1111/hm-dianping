# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

hm-dianping is a local-life review platform (Dianping/Meituan clone) — a teaching project from 黑马程序员. Java 8 + Spring Boot 2.3.12, using MyBatis-Plus and Redis. Many core features (login, seckill, follow, comments) are TODO stubs for students to complete.

## Build & Run Commands

```bash
# Build
mvn clean package

# Run (port 8081)
mvn spring-boot:run
# or
java -jar target/hm-dianping-0.0.1-SNAPSHOT.jar

# Test (currently empty — no tests implemented)
mvn test
```

## Prerequisites

- Java 8 (JDK 1.8)
- MySQL on `localhost:3306`, database `hmdp` — import schema from `src/main/resources/db/hmdp.sql`
- Redis on `localhost:6379`
- Nginx for frontend serving (image uploads go to `D:\lesson\nginx-1.18.0\html\hmdp\imgs\`)

## Architecture

**Layered MVC:** Controller → Service → Mapper (MyBatis-Plus)

- **Controllers** (`@RestController`): REST endpoints, all responses wrapped in `Result` DTO
- **Services**: interfaces extend `IService<T>`, implementations extend `ServiceImpl<Mapper, Entity>`
- **Mappers**: extend `BaseMapper<T>` for auto-CRUD; one custom XML mapper at `resources/mapper/VoucherMapper.xml`
- **Entities**: Lombok `@Data` + MyBatis-Plus `@TableName`/`@TableId` annotations

**Key patterns:**
- `Result` — unified API response (`success`, `errorMsg`, `data`, `total`)
- `UserHolder` — ThreadLocal storing current `UserDTO` per request
- `RedisConstants` — all Redis key prefixes and TTLs (shop cache, login tokens, seckill stock, blog likes, feed, geo, sign-in bitmaps)
- `RedisData` — wrapper for logical-expiration cache (anti-stampede)
- Distributed lock keys (`lock:shop:`) for cache update synchronization

## Database Tables

`tb_user`, `tb_user_info`, `tb_shop` (with geo coords), `tb_shop_type`, `tb_blog`, `tb_blog_comments`, `tb_follow`, `tb_voucher`, `tb_seckill_voucher`, `tb_voucher_order`, `tb_sign`

## Key Entry Points

- App: `com.hmdp.HmDianPingApplication`
- Config: `com.hmdp.config.MybatisConfig` (pagination), `WebExceptionAdvice` (global error handler)
- Constants: `com.hmdp.utils.SystemConstants`, `RedisConstants`
- Auth context: `com.hmdp.utils.UserHolder`