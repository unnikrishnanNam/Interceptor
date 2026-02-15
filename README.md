# Interceptor

<div align="center">

**A PostgreSQL Peer Authorization Proxy**

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.1-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue.svg)](https://www.postgresql.org/)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

*Intercept, authorize, and audit PostgreSQL queries in real-time with peer approval workflows*

</div>

---

## 📖 Overview

**Interceptor** is a sophisticated database authorization proxy that sits between your applications and PostgreSQL databases, providing real-time query interception, peer-based approval workflows, and comprehensive audit logging. Built with enterprise-grade security and scalability in mind, it enables teams to implement database access controls without modifying application code.

### Key Features

- 🔒 **Real-time Query Interception** - Intercepts and analyzes SQL queries before they reach your database
- 👥 **Peer Approval Workflow** - Implements configurable peer-based authorization for critical operations
- 🔍 **Intelligent SQL Classification** - Keyword-based query classification (CRITICAL, ALLOWED, DEFAULT)
- 📊 **Live Dashboard** - Real-time monitoring and approval interface via WebSocket
- 🔐 **Multiple Authentication Methods** - Supports both password-based and OAuth2 authentication
- 📝 **Comprehensive Audit Logging** - Complete audit trail of all queries and approval decisions
- 🚀 **High Performance** - Built on Netty for non-blocking, asynchronous I/O
- 🔒 **SSL/TLS Support** - Full encryption support for client and database connections
- 🛡️ **Replay Attack Protection** - Nonce-based security to prevent replay attacks
- ⚡ **Redis-backed Notifications** - Real-time pub/sub for instant updates

---

## 🏗️ Architecture

```
┌─────────────┐         ┌──────────────────┐         ┌──────────────┐
│             │         │                  │         │              │
│   Client    ├────────►│   Interceptor    ├────────►│  PostgreSQL  │
│ Application │  :5432  │      Proxy       │  :5433  │   Database   │
│             │         │                  │         │              │
└─────────────┘         └──────────────────┘         └──────────────┘
                               │
                               │
                        ┌──────▼───────┐
                        │              │
                        │  Dashboard   │
                        │  (WebSocket) │
                        │   :3000      │
                        │              │
                        └──────────────┘
```

### Components

1. **Proxy Server** (Netty-based TCP proxy)
   - Listens on port 5432 (configurable)
   - Intercepts PostgreSQL wire protocol messages
   - Handles SSL negotiation and encryption
   - Routes queries to the target database

2. **SQL Classifier**
   - Analyzes queries using keyword matching
   - Classifies as CRITICAL, ALLOWED, or DEFAULT
   - Configurable keyword lists and default policy

3. **Approval Service**
   - Manages blocked queries awaiting approval
   - Implements peer voting mechanism
   - Handles immediate approval by admins
   - Sends real-time notifications via WebSocket

4. **Audit System**
   - Logs all queries, approvals, and rejections
   - Tracks user actions with IP addresses
   - Replay attack detection and prevention

5. **Dashboard API**
   - RESTful API for configuration and management
   - WebSocket endpoints for real-time updates
   - JWT-based authentication

---

## 🚀 Quick Start

### Prerequisites

- Java 21 or higher
- Docker and Docker Compose
- Maven 3.6+ (or use the included Maven wrapper)

### Installation

1. **Clone the repository**
   ```bash
   git clone https://github.com/gautamrajesh007/Interceptor.git
   cd Interceptor
   ```

2. **Start infrastructure services**
   ```bash
   docker-compose up -d
   ```
   This starts:
   - PostgreSQL target database (port 5433)
   - PostgreSQL for Interceptor metadata (port 5434)
   - Redis for pub/sub (port 6379)

3. **Build the application**
   ```bash
   ./mvnw clean package -DskipTests
   ```

4. **Run the proxy**
   ```bash
   ./mvnw spring-boot:run
   ```

5. **Access the dashboard**
   ```
   Dashboard: http://localhost:3000
   Default credentials: admin / 14495abc
   ```

### Docker Compose Services

The included `docker-compose.yml` provides:

```yaml
services:
  # Target PostgreSQL (protected database)
  postgres-target:
    - Port: 5433
    - Database: testdb
    - User: testuser / testpass@123

  # Interceptor's metadata database
  postgres-interceptor:
    - Port: 5434
    - Database: interceptor
    - User: interceptor / interceptor123

  # Redis for real-time features
  redis:
    - Port: 6379
```

---

## ⚙️ Configuration

Configure the proxy via `application.properties` or `application.yml`:

### Proxy Settings

```properties
# Proxy listening port (clients connect here)
proxy.listen-port=5432

# Target PostgreSQL database
proxy.target-host=localhost
proxy.target-port=5433

# Query classification
proxy.block-by-default=false
proxy.critical-keywords=DROP,DELETE,TRUNCATE,ALTER,GRANT
proxy.allowed-keywords=SELECT,INSERT,UPDATE

# SSL/TLS configuration
proxy.ssl.enabled=false
proxy.ssl.key-store=classpath:keystore.p12
proxy.ssl.key-store-password=changeit
proxy.ssl.client-auth=false
```

### Approval Workflow

```properties
# Enable peer approval workflow
approval.peer-enabled=true

# Minimum number of approvals required
approval.min-votes=2
```

### Database Configuration

```properties
# Interceptor's metadata database
spring.datasource.url=jdbc:postgresql://localhost:5434/interceptor
spring.datasource.username=interceptor
spring.datasource.password=interceptor123

# Redis for pub/sub
spring.data.redis.host=localhost
spring.data.redis.port=6379
```

### JWT Authentication

```properties
jwt.secret=your-secret-key-change-in-production
jwt.expiration=86400000
```

---

## 📚 Usage

### Connecting Applications

Simply point your application's database connection to the proxy:

```java
// Before: Direct connection
jdbc:postgresql://localhost:5433/testdb

// After: Through Interceptor
jdbc:postgresql://localhost:5432/testdb
```

No application code changes required!

### Query Classification

Queries are classified based on keywords:

1. **CRITICAL** - Requires approval
   - Matches any keyword in `proxy.critical-keywords`
   - Example: `DROP TABLE users`, `DELETE FROM orders`

2. **ALLOWED** - Passes through
   - Matches any keyword in `proxy.allowed-keywords`
   - Example: `SELECT * FROM products`

3. **DEFAULT** - Follows `proxy.block-by-default` policy
   - No keyword match
   - Example: Custom functions, stored procedures

### Approval Workflow

#### For Admin Users
- **Immediate approval/rejection** of any blocked query
- No peer voting required

#### For Peer Users (when peer approval enabled)
- **Vote** on blocked queries (APPROVE or REJECT)
- Query executes when `approval.min-votes` approvals reached
- Query cancelled if any rejection vote cast

### REST API Examples

#### Login
```bash
curl -X POST http://localhost:8080/api/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"14495abc"}'
```

#### Get Pending Queries
```bash
curl http://localhost:8080/api/pending \
  -H "Authorization: Bearer <token>"
```

#### Approve Query
```bash
curl -X POST http://localhost:8080/api/approve \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"id":1,"nonce":"unique-nonce","timestamp":"2026-02-15T10:00:00Z"}'
```

#### Vote on Query (Peer Mode)
```bash
curl -X POST http://localhost:8080/api/vote \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"id":1,"vote":"APPROVE"}'
```

---

## 🔐 Security Features

### SSL/TLS Support
- Client-to-proxy encryption
- Proxy-to-database encryption
- Optional client certificate authentication

### Authentication
- **Local Authentication**: Username/password with BCrypt hashing
- **OAuth2**: GitHub, Google, and other providers supported
- **JWT Tokens**: Stateless session management

### Replay Attack Protection
- Nonce-based request validation
- Timestamp verification
- Request hash tracking in audit log

### Audit Trail
Every action is logged:
- Query interceptions
- Approval/rejection decisions
- User logins and logouts
- Configuration changes
- Failed authentication attempts

---

## 🛠️ Technology Stack

- **Core Framework**: Spring Boot 4.0.1
- **Network Layer**: Netty (async I/O)
- **Database**: PostgreSQL 16
- **Cache/PubSub**: Redis 7
- **Security**: Spring Security + JWT
- **ORM**: Spring Data JPA + Hibernate
- **Protocol**: PostgreSQL Wire Protocol
- **Real-time**: WebSocket (STOMP)
- **Build Tool**: Maven

---

## 📊 Monitoring & Observability

### Spring Boot Actuator Endpoints
```bash
# Health check
curl http://localhost:8080/actuator/health

# Metrics
curl http://localhost:8080/actuator/metrics

# Application info
curl http://localhost:8080/actuator/info
```

### Metrics Tracked
- Total queries processed
- Blocked queries count
- Approval/rejection rates
- Active connections
- Query latency

---

## 🧪 Development

### Running Tests
```bash
./mvnw test
```

### Building for Production
```bash
./mvnw clean package -DskipTests
java -jar target/interceptor-0.0.1-SNAPSHOT.jar
```

### Hot Reload (Development)
Spring Boot DevTools is included for automatic restart during development.

---

## 📁 Project Structure

```
src/main/java/com/proxy/interceptor/
├── config/              # Configuration classes (Security, SSL, WebSocket)
├── controller/          # REST API controllers
├── dto/                 # Data transfer objects
├── model/               # JPA entities
├── proxy/               # Core proxy logic (Netty handlers)
│   ├── ProxyServer.java          # Main proxy server
│   ├── ClientHandler.java        # Client connection handler
│   ├── SqlClassifier.java        # Query classification
│   └── WireProtocolHandler.java  # PostgreSQL protocol parser
├── repository/          # JPA repositories
├── security/            # JWT and authentication
├── service/             # Business logic
│   ├── AuthService.java
│   ├── BlockedQueryService.java
│   ├── AuditService.java
│   └── MetricsService.java
└── InterceptorApplication.java   # Main entry point
```

---

## ���� Contributing

Contributions are welcome! Please feel free to submit a Pull Request. For major changes, please open an issue first to discuss what you would like to change.

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit your changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

---

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

---

## 🙏 Acknowledgments

- Built with [Spring Boot](https://spring.io/projects/spring-boot)
- Powered by [Netty](https://netty.io/)
- PostgreSQL wire protocol implementation inspired by the community

---

## 📧 Contact

**Gautam Rajesh** - [@gautamrajesh007](https://github.com/gautamrajesh007)

Project Link: [https://github.com/gautamrajesh007/Interceptor](https://github.com/gautamrajesh007/Interceptor)

---

<div align="center">

**⭐ Star this repository if you find it helpful!**

Made with ❤️ for database security

</div>