# Security model: DataWave

This is the threat model security scans of this repository start from. It was written from the code and the maintainers' answers (2026-11-05): the whole repository is in scope, all four attacker profiles matter, and nothing is out of scope or accepted risk. There is no separate infrastructure repository; the only deployment code is the local-development `docker/docker-compose*.yml` and `contrib/datawave-quickstart`. Keep it up to date as the system changes.

## System overview
DataWave is an ingest and query framework on top of Apache Accumulo and Hadoop. Data is ingested by MapReduce jobs into sharded Accumulo tables (shard, shardIndex, DatawaveMetadata, edge tables). Every cell carries an Accumulo visibility label. Users query through either the legacy WildFly/Jakarta EE web services or the Spring Boot microservices. Queries are parsed into JEXL trees, planned (`DefaultQueryPlanner`) and executed server-side inside Accumulo tablet servers by the `QueryIterator` stack. Results are filtered by the caller's Accumulo authorizations.

## Components
| Name | Code |
|---|---|
| Auth | `web-services/security-parent/**`, `microservices/services/authorization/**`, `microservices/starters/datawave/**/authorization/**`, `microservices/starters/datawave/**/config/security/**`, `web-services/common-util/**/security/**` |
| Web services | `web-services/**` except `security-parent` and `deploy` (REST beans: `QueryExecutorBean`, `ModelBean`, `TableAdminBean`, `PermissionsBean`, `ModificationBean`, `CachedResultsBean`, ...) |
| Query microservices | `microservices/services/{query,query-executor,mapreduce-query,query-metric}/**`, `microservices/starters/{query,cached-results,query-metric}/**` |
| Supporting microservices | `microservices/services/{accumulo,audit,annotation,annotation-cache,dictionary,modification,file-provider,hazelcast,config}/**`, `microservices/starters/{audit,cache,metadata,datawave}/**` (except the Auth paths) |
| Query engine | `warehouse/query-core/**`, `core/query/**`, `warehouse/accumulo-extensions/**`, `warehouse/age-off*/**`, `warehouse/*-dictionary-core/**`, `warehouse/edge-model-configuration-core/**` |
| Ingest | `warehouse/ingest-*/**`, `warehouse/ops-tools/**`, `warehouse/index-stats/**`, `core/map-reduce/**` |
| Core libraries | `core/**` (except `core/query`, `core/map-reduce`), `warehouse/core/**`, `warehouse/common/**`, `commons/**` |
| Deployment & dev tooling | `docker/**`, `contrib/**`, `web-services/deploy/**`, `properties/**`, `microservices/configcheck/**`, build files |

## Trust boundaries and entry points
- **Client to front door (web services on 8443, microservice gateway):** mutual TLS with X.509 client certificates. `AllowedCallersFilter` (microservices) only enforces the allowed-callers list when a certificate is present and `enforce-allowed-callers` is on.
- **Trusted proxies:** a server that is an allowed caller can act for end users via `X-ProxiedEntitiesChain` / `X-ProxiedIssuersChain` (`ProxiedEntityX509Filter`, `DatawaveHttpAuthenticationMechanism`). When `useTrustedSubjectHeaders` is on, the caller's own identity can come from `X-SSL-clientcert-subject` / `X-SSL-clientcert-issuer` headers set by a TLS-terminating load balancer. Header trust is the main identity boundary.
- **Service to service:** the authorization service issues JWTs (default TTL 1 day, `docker/config/application.yml`); other microservices accept them via `JWTAuthenticationFilter` / `JWTSecurityConfigurer`. OAuth endpoints in `OAuthControllerV2`.
- **User query to tablet server:** user-supplied JEXL/Lucene query text and parameters are parsed and shipped as iterator options into Accumulo tablet servers, which run with the system's full authorizations; the iterators must apply the caller's authorizations.
- **Ingest input to Accumulo:** files (CSV, JSON, Wikipedia XML, NYC TLC, ssdeep) parsed by MapReduce jobs; the visibility labels written come from the ingest configuration and the data.
- **Internal messaging and caches:** RabbitMQ/Kafka (query results, audit), Hazelcast (cache, query state), Consul, Spring Cloud Config server.
- **Admin endpoints:** table admin, permissions, modification, audit replay, cache management, `@RolesAllowed`/`@PreAuthorize` guarded.

## Assets
- Data in Accumulo protected by cell-level visibility labels (highest value).
- User identities and authorizations (DatawaveUser, cached in Hazelcast / `DatawaveUserCache`).
- JWT signing keys, server keystores, Accumulo/Hadoop credentials.
- Audit records (required for accountability).
- Query metadata (query text, which can itself be sensitive) and cached results.

## Attacker profiles (all in scope)
1. Unauthenticated network user reaching an exposed port.
2. Authenticated analyst trying to read data above their authorizations, act as another user, or reach admin functions.
3. Malicious or malformed ingest data.
4. A compromised internal service or a misconfigured trusted proxy.

## Existing controls
- mTLS client certificates; allowed-callers list; proxied-entity chain validation (issuer/entity count check).
- Accumulo visibility labels enforced via the caller's authorizations; `AuthorizationsPredicate`, `WSAuthorizationsUtil` minimization across proxied entities.
- Role checks (`@RolesAllowed`, `@PreAuthorize`) on admin endpoints; `DeniedAccessRoleFilter`.
- Audit service records queries before execution.

## Out of scope / accepted risk
- None. Everything is reported, including development tooling (`docker/`, `contrib/`, the test PKI in `docker/pki`) and admin-only endpoints.

## Finding tags
- `Component`: one of the component names above.
- `CWE`: the CWE ID of the weakness, for example `CWE-639`.
- `CVE`: the CVE ID, for findings about a vulnerable dependency.

## Open questions
- None outstanding.
