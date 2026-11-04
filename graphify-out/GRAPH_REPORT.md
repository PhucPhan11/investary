# Graph Report - investary  (2026-10-30)

## Corpus Check
- 356 files · ~107,753 words
- Verdict: corpus is large enough that graph structure adds value.
- Unclassified: 22 file(s) not represented in the graph (top: .xml 10, (none) 5, .properties 3)

## Summary
- 3276 nodes · 9713 edges · 139 communities (98 shown, 41 thin omitted)
- Extraction: 92% EXTRACTED · 8% INFERRED · 0% AMBIGUOUS · INFERRED: 810 edges (avg confidence: 0.85)
- Token cost: 321,021 input · 0 output

## Community Hubs (Navigation)
- ApiException Error Model
- Frontend Routes & Models
- Portfolio API Client
- Assistant UI & Knowledge API
- Research Extraction Domain
- Document & Email Controllers
- Investor Profile Enums UI
- Test Mocking Helpers
- Document Integration Tests
- Intelligence API Requests
- REST Delete Endpoints
- Persistence Repositories & Views
- ArchitectureTest Module Rules
- Email Ingestion Integration Test
- HoldingCalculator Replay
- Portfolio Analysis Snapshot
- Impact Assessment Service
- Angular Workspace Config
- PDF Signature Validation
- Investor Profile Service
- Document Service Operations
- Text Extraction Results
- Source Message Domain
- Instrument Service Errors
- Frontend Package Dependencies
- Controller Tests & Enums
- Investor Goals API
- Intelligence Prompts
- Intelligence API Client
- AfterCommit Transactions
- Document Upload & Hashing
- EML Test Fixtures
- Email API Client
- Knowledge Index Worker
- Embedding Service
- pgVector & AI Errors
- Research Matching Focus
- Chat Gateway & Events
- Assessment Planning
- Knowledge Status Queries
- Intelligence Controller Wiring
- Impact Assessment Items
- Fake Embedding Test Support
- Email Content Analyzer
- Research Match Kinds
- Portfolio Commands
- Source Link Views
- EML Parser
- Instrument Controller
- Download Exceptions
- Module Architecture Docs
- URL Codec
- Research Assistant Service
- Text Chunker
- Source Link Downloads
- Toolchain & Harness Scripts
- Hybrid Search Filters
- Exchange Enum
- Report Downloader Tests
- Portfolio & RAG Docs
- Data Ownership Principles
- Instrument Prices
- Citation Validator
- Ollama AI Config
- Knowledge Chunking Config
- App Storage & Brokerage Config
- Docker Postgres Setup
- Global Exception Handler
- Document Controller Tests
- Research Ingestion Persistence
- Text Cleaner
- Intelligence Integration Test
- Agent Operating Rules
- DNS Address Restriction
- Email Properties & CORS
- Email Parsing Status
- Link Classifier
- Datasource Config
- URL Policy
- Dev Profile Config
- Web & Download Config
- Portfolio Transactions Service
- Transaction Domain
- Email Controller Tests
- Download Job Queue
- Knowledge Chunk Store
- Index Version
- Untrusted Input Docs
- Instrument Entity
- Report Type & Upload Metadata
- Download Request Service
- Application Bootstrap
- Logging Config
- Download Policy Config
- Email Inbox UI
- Email Text Extractor
- Knowledge Controller
- Flyway Upgrade Test
- DNS Resolver Tests
- AI Knowledge Rules
- HTML Sanitizer Tests
- Download Jobs & Index ADRs
- Link Category
- Portfolio Valuator Tests
- Duplicate & EML ADRs
- Phase 2 Email Plan
- Angular App Shell
- Portfolio Dashboard UI
- Frontend Conventions
- Link Status
- Worker Config
- Modular Monolith ADRs
- Upload Panel UI
- Ticker Detector
- Reindex Scope
- SSRF Download ADR
- Citation Integrity ADR
- Email Import Panel
- PDF Validator Tests
- Language Detector
- Date Range Validation
- AI Retry Config
- Portfolio Accounting ADR
- Toolchain Failure Note
- Blocked Address Exception
- Pagination Config
- Module Cycle Failure
- Market Date Failure
- Embedding Exception
- Embedding Unavailable
- Brand Mark Favicon
- Browser Iframe Failure
- Index Host Page
- Backend Maven Artifact

## God Nodes (most connected - your core abstractions)
1. `DocumentService` - 52 edges
2. `errorMessage()` - 48 edges
3. `ApiException` - 47 edges
4. `PortfolioQueryService` - 46 edges
5. `ErrorCode` - 45 edges
6. `KnowledgeProperties` - 43 edges
7. `SearchHit` - 43 edges
8. `KnowledgeBaseIntegrationTest` - 42 edges
9. `@angular/core` - 42 edges
10. `HybridSearchService` - 40 edges

## Surprising Connections (you probably didn't know these)
- `Security posture` --semantically_similar_to--> `Untrusted input rules`  [INFERRED] [semantically similar]
  docs/architecture.md → AGENTS.md
- `Original files are sources of truth` --semantically_similar_to--> `Data ownership (authoritative vs derived)`  [INFERRED] [semantically similar]
  AGENTS.md → docs/architecture.md
- `Portfolio rules` --semantically_similar_to--> `No fictional P&L`  [INFERRED] [semantically similar]
  AGENTS.md → docs/phase-4-implementation.md
- `Security posture` --semantically_similar_to--> `Local single-user, no authentication`  [INFERRED] [semantically similar]
  docs/architecture.md → README.md
- `Database-backed download job queue` --semantically_similar_to--> `FOR UPDATE SKIP LOCKED job claims with lease and token`  [INFERRED] [semantically similar]
  README.md → docs/phase-2-implementation.md

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Knowledge indexing pipeline** — docs_phase_3_implementation_knowledgeindexworker, docs_phase_3_implementation_knowledgeindexprocessor, docs_phase_3_implementation_pdftextextractor, docs_phase_3_implementation_emailtextextractor, docs_phase_3_implementation_textchunker, docs_phase_3_implementation_embeddingservice, docs_phase_3_implementation_knowledgechunkstore [EXTRACTED 1.00]
- **RAG answer and citation validation flow** — docs_phase_3_implementation_hybridsearchservice, docs_phase_3_implementation_promptbuilder, docs_phase_3_implementation_researchassistantservice, docs_phase_3_implementation_citationvalidator, docs_phase_4_implementation_chatgateway, docs_phase_4_implementation_impactassessmentservice, docs_phase_4_implementation_portfolioassistantservice [EXTRACTED 1.00]
- **Secure report download defenses** — docs_architecture_reportdownloader, docs_architecture_urlpolicy, docs_architecture_restrictedaddressdnsresolver, docs_phase_2_implementation_apache_httpclient5_downloader, docs_architecture_downloadjobworker [EXTRACTED 1.00]
- **Layered defense for downloading untrusted email URLs** — docs_adr_0004_report_download_policy_user_initiated_downloads, docs_adr_0004_report_download_policy_tracking_link_unwrapping, docs_adr_0004_report_download_policy_url_policy, docs_adr_0004_report_download_policy_restricted_address_dns_resolver, docs_adr_0004_report_download_policy_content_policy [EXTRACTED 1.00]
- **Citation integrity pipeline** — docs_adr_0006_citation_integrity_backend_owned_citation_identity, docs_adr_0006_citation_integrity_citationvalidator, docs_adr_0006_citation_integrity_evidence_states, docs_adr_0006_citation_integrity_system_message_instructions, docs_adr_0006_citation_integrity_third_party_opinion_attribution [EXTRACTED 1.00]
- **Portfolio accounting model** — docs_adr_0007_portfolio_accounting_transactions_source_of_truth, docs_adr_0007_portfolio_accounting_holdingcalculator, docs_adr_0007_portfolio_accounting_weighted_average_cost, docs_adr_0007_portfolio_accounting_chronological_replay, docs_adr_0007_portfolio_accounting_bigdecimal_decimal128, docs_adr_0007_portfolio_accounting_manual_prices [EXTRACTED 1.00]
- **Portfolio dashboard page composition (holdings, transactions, pricing, research insights)** — frontend_src_app_features_portfolio_portfolio_dashboard, frontend_src_app_features_portfolio_transaction_form, frontend_src_app_features_portfolio_research_insights, frontend_src_app_features_portfolio_portfolio_dashboard_holdings_table, frontend_src_app_features_portfolio_portfolio_dashboard_manual_price_form [EXTRACTED 1.00]
- **Evidence-backed AI output display with not-advice disclaimers** — frontend_src_app_features_assistant_research_assistant_citation_buttons, frontend_src_app_features_assistant_research_assistant_sources_panel, frontend_src_app_features_portfolio_research_insights_impact_assessments, frontend_src_app_features_research_not_advice_disclaimer [INFERRED 0.85]
- **Email import to research link download to document library flow** — frontend_src_app_features_emails_email_inbox, frontend_src_app_features_emails_email_detail_research_links_table, frontend_src_app_features_emails_email_detail_linked_reports, frontend_src_app_features_documents_document_library [INFERRED 0.85]

## Communities (139 total, 41 thin omitted)

### Community 0 - "ApiException Error Model"
Cohesion: 0.03
Nodes (46): ApiException, ErrorCode, AI_NOT_CONFIGURED, AI_PROVIDER_ERROR, AI_TIMEOUT, ASSESSMENT_NOT_FOUND, CURRENCY_MISMATCH, DOCUMENT_NOT_FOUND (+38 more)

### Community 1 - "Frontend Routes & Models"
Cohesion: 0.07
Nodes (44): RFC-9457, DocumentApiService, DOCUMENTS_API, EMAILS_API, Page, ProblemDetail, REPORT_TYPES, ReportType (+36 more)

### Community 2 - "Portfolio API Client"
Cohesion: 0.05
Nodes (41): INSTRUMENT_API, InstrumentApiService, INVESTOR_API, InvestorApiService, PORTFOLIO_API, PortfolioApiService, Allocation, AllocationBasis (+33 more)

### Community 3 - "Assistant UI & Knowledge API"
Cohesion: 0.05
Nodes (34): clean(), KNOWLEDGE_API, KnowledgeApiService, RESEARCH_API, ResearchApiService, segment(), AssistantAnswer, IndexStatus (+26 more)

### Community 4 - "Research Extraction Domain"
Cohesion: 0.08
Nodes (9): SourceAttachment, ResearchItemView, ResearchItem, ResearchItemType, COMPANY_NEWS, INDUSTRY_UPDATE, MACRO_UPDATE, MARKET_UPDATE (+1 more)

### Community 5 - "Document & Email Controllers"
Cohesion: 0.06
Nodes (12): PageResponse, DocumentController, DocumentResponse, DocumentDownload, EmailController, Attachment, EmailDetail, EmailSummary (+4 more)

### Community 6 - "Investor Profile Enums UI"
Cohesion: 0.06
Nodes (16): errorMessage(), ExperienceLevel, GoalPriority, InstrumentType, InvestmentHorizon, RiskTolerance, Dashboard, DocumentDetail (+8 more)

### Community 7 - "Test Mocking Helpers"
Cohesion: 0.07
Nodes (5): AiExceptions, AiNotConfiguredException, AiTimeoutException, IntelligenceTestData, TestKnowledgeProperties

### Community 8 - "Document Integration Tests"
Cohesion: 0.12
Nodes (5): DocumentIntegrationTest, KnowledgeEvaluationTest, PortfolioIntegrationTest, DownloadJobQueueTest, TestDatabase

### Community 9 - "Intelligence API Requests"
Cohesion: 0.08
Nodes (21): AskRequest, FilterRequest, IntelligenceController, TypeReference<List<String>>, AssessmentItemView, AssessmentListItem, AssessmentSnapshot, AssessmentView (+13 more)

### Community 10 - "REST Delete Endpoints"
Cohesion: 0.11
Nodes (8): PortfolioController, PortfolioRequest, TransactionRequest, TransactionView, TransactionType, BUY, OPENING_POSITION, SELL

### Community 11 - "Persistence Repositories & Views"
Cohesion: 0.10
Nodes (5): AssistantAnswer, Citation, KnowledgeSourceType, DOCUMENT, EMAIL

### Community 12 - "ArchitectureTest Module Rules"
Cohesion: 0.06
Nodes (7): ArchitectureTest, Dependency, ContentAnalyzerTest, IntelligenceControllerTest, InvestorControllerTest, PortfolioControllerTest, ResearchItemExtractorTest

### Community 13 - "Email Ingestion Integration Test"
Cohesion: 0.10
Nodes (4): EmailIngestionIntegrationTest, EmlParserTest, Builder, SyntheticResearchCorpus

### Community 14 - "HoldingCalculator Replay"
Cohesion: 0.11
Nodes (8): HoldingCalculator, Oversell, Position, Trade, Money, PortfolioValuator, Row, HoldingCalculatorTest

### Community 15 - "Portfolio Analysis Snapshot"
Cohesion: 0.08
Nodes (27): Observation, PortfolioAnalysis, ResearchCoverage, Severity, ATTENTION, INFO, PortfolioAnswer, PortfolioResearch (+19 more)

### Community 16 - "Impact Assessment Service"
Cohesion: 0.10
Nodes (6): Hashing, ChatGateway, Citations, EmbeddingDimensionMismatchException, ExtractionException, SearchResult

### Community 17 - "Angular Workspace Config"
Cohesion: 0.05
Nodes (43): build, serve, test, builder, configurations, defaultConfiguration, options, cli (+35 more)

### Community 19 - "Investor Profile Service"
Cohesion: 0.08
Nodes (17): ProfileRequest, ProfileCommand, ProfileView, ExperienceLevel, ADVANCED, BEGINNER, INTERMEDIATE, NONE (+9 more)

### Community 21 - "Text Extraction Results"
Cohesion: 0.08
Nodes (14): ExtractedText, SkipReason, NO_TEXT, OCR_REQUIRED, PARSING_FAILED, PdfTextExtractor, SourceLoader, DateBasis (+6 more)

### Community 22 - "Source Message Domain"
Cohesion: 0.11
Nodes (4): SourceMessage, SourceType, EML_UPLOAD, SourceMessageSpecifications

### Community 23 - "Instrument Service Errors"
Cohesion: 0.07
Nodes (12): CurrencyMismatchException, DuplicateInstrumentException, DuplicatePortfolioException, DuplicatePriceException, FutureDateException, InstrumentInUseException, InstrumentNotFoundException, InsufficientHoldingsException (+4 more)

### Community 24 - "Frontend Package Dependencies"
Cohesion: 0.07
Nodes (38): dependencies, @angular/common@^22.2.0, @angular/compiler@^22.2.0, @angular/core@^22.2.0, @angular/forms@^22.2.0, @angular/platform-browser@^22.2.0, @angular/router@^22.2.0, rxjs@~7.8.0 (+30 more)

### Community 26 - "Investor Goals API"
Cohesion: 0.11
Nodes (10): GoalRequest, InvestorController, GoalCommand, GoalView, InvestorProfileService, GoalPriority, HIGH, LOW (+2 more)

### Community 27 - "Intelligence Prompts"
Cohesion: 0.10
Nodes (6): HoldingPrompt, IntelligencePrompts, PortfolioContext, Excerpts, LabeledHit, PromptBuilder

### Community 28 - "Intelligence API Client"
Cohesion: 0.10
Nodes (22): clean(), INTELLIGENCE_API, IntelligenceApiService, AiStatus, Assessment, AssessmentItem, AssessmentListItem, EvidenceReference (+14 more)

### Community 29 - "AfterCommit Transactions"
Cohesion: 0.10
Nodes (8): AfterCommit, TransactionSynchronization, EmailDeletionService, EmailImportService, EmailQueryService, Original, SourceAttachmentRepository, SourceMessageRepository

### Community 30 - "Document Upload & Hashing"
Cohesion: 0.10
Nodes (10): ContentInfo, DocumentService, UploadDocumentCommand, DocumentRepository, MalformedEmailException, Association, ProvenanceRepository, RelationshipType (+2 more)

### Community 32 - "Email API Client"
Cohesion: 0.09
Nodes (6): EmailApiService, ResearchItem, SourceLink, EmailDetail, email, indexed

### Community 33 - "Knowledge Index Worker"
Cohesion: 0.14
Nodes (5): KnowledgeIndexProcessor, ClaimedSource, KnowledgeIndexQueue, KnowledgeIndexWorker, KnowledgeTestSupport

### Community 34 - "Embedding Service"
Cohesion: 0.12
Nodes (5): EmbeddingService, Sleeper, Embedding, EmbeddingServiceTest, FakeEmbeddingModel

### Community 35 - "pgVector & AI Errors"
Cohesion: 0.09
Nodes (4): AiProviderException, PgVectors, ChunkLookupService, BrokerageRegistry

### Community 36 - "Research Matching Focus"
Cohesion: 0.16
Nodes (3): ResearchFilters, PortfolioAssistantServiceTest, ResearchMatcherTest

### Community 37 - "Chat Gateway & Events"
Cohesion: 0.11
Nodes (5): DocumentDeletedEvent, DocumentDeletionListener, DownloadJobProcessor, DownloadJobWorker, Worker

### Community 38 - "Assessment Planning"
Cohesion: 0.19
Nodes (9): Plan, Tier, DIRECT, MARKET, MENTION, NONE, HoldingResearch, MatchedEvidence (+1 more)

### Community 39 - "Knowledge Status Queries"
Cohesion: 0.09
Nodes (10): KnowledgeIndexService, KnowledgeJob, KnowledgeSourceStatus, FAILED, INDEXED, PENDING, PROCESSING, SKIPPED (+2 more)

### Community 40 - "Intelligence Controller Wiring"
Cohesion: 0.17
Nodes (12): ImpactAssessmentService, IntelligenceProperties, PortfolioAnalysisService, PortfolioAssistantService, ResearchMatcher, ImpactAssessmentItemRepository, ImpactAssessmentRepository, InvestorQueryService (+4 more)

### Community 41 - "Impact Assessment Items"
Cohesion: 0.08
Nodes (12): ImpactAssessmentItem, ImpactCategory, DIRECT_HOLDING, INSUFFICIENT_EVIDENCE, MARKET_CONTEXT, MONITORING_ONLY, POTENTIAL_OPPORTUNITY, POTENTIAL_RISK (+4 more)

### Community 43 - "Email Content Analyzer"
Cohesion: 0.18
Nodes (6): Analysis, AnalyzedContent, ContentAnalyzer, MutableLink, State, ExtractedLink

### Community 44 - "Research Match Kinds"
Cohesion: 0.11
Nodes (14): MatchKind, DIRECT, MARKET_CONTEXT, MENTION, ResearchController, SearchHitView, SearchRequest, SearchResponse (+6 more)

### Community 45 - "Portfolio Commands"
Cohesion: 0.12
Nodes (4): PortfolioCommand, PortfolioView, Portfolio, PortfolioRepository

### Community 46 - "Source Link Views"
Cohesion: 0.12
Nodes (8): Download, SourceLinkView, DownloadJob, DownloadJobStatus, FAILED, PENDING, RUNNING, SUCCEEDED

### Community 47 - "EML Parser"
Cohesion: 0.19
Nodes (5): Bodies, EmlParser, Sender, ParsedAttachment, ParsedEmail

### Community 48 - "Instrument Controller"
Cohesion: 0.16
Nodes (8): InstrumentController, InstrumentRequest, PriceRequest, InstrumentService, InstrumentCommand, PortfolioCommands, PriceCommand, InstrumentView

### Community 49 - "Download Exceptions"
Cohesion: 0.17
Nodes (10): DownloadException, Kind, BLOCKED, PERMANENT, TRANSIENT, Done, DownloadedFile, Redirect (+2 more)

### Community 50 - "Module Architecture Docs"
Cohesion: 0.13
Nodes (23): common.tx.AfterCommit, common module, ContentAnalyzer, document module, DocumentDeletedEvent, email module, EmailQueryService, EmlParser (+15 more)

### Community 51 - "URL Codec"
Cohesion: 0.14
Nodes (3): UrlCodec, HtmlSanitizer, TrackingLinkUnwrapper

### Community 52 - "Research Assistant Service"
Cohesion: 0.19
Nodes (3): ModelAnswer, ResearchAssistantService, ResearchAssistantServiceTest

### Community 53 - "Text Chunker"
Cohesion: 0.17
Nodes (4): Chunk, Piece, TextChunker, TextChunkerTest

### Community 55 - "Toolchain & Harness Scripts"
Cohesion: 0.11
Nodes (18): IntelliJ run configurations (.run/), Maven wrapper (./mvnw), AGENT_DOCS, checkDocsDrift(), checkFailureMemory(), checkMigrations(), checkSensitiveFiles(), CHECKSUMS_FILE (+10 more)

### Community 56 - "Hybrid Search Filters"
Cohesion: 0.12
Nodes (4): QueryAnalysis, SearchFilters, ResearchControllerTest, QueryAnalysisTest

### Community 57 - "Exchange Enum"
Cohesion: 0.12
Nodes (11): Exchange, HNX, HOSE, OTHER, UPCOM, InstrumentType, BOND, ETF (+3 more)

### Community 59 - "Portfolio & RAG Docs"
Cohesion: 0.16
Nodes (15): InvestorQueryService, PortfolioQueryService, HybridSearchService, Reciprocal Rank Fusion (k=60), ResearchAssistantService, ChatGateway, HoldingCalculator, Impact categories per evidence tier (+7 more)

### Community 60 - "Data Ownership Principles"
Cohesion: 0.11
Nodes (17): Data ownership (authoritative vs derived), message_documents provenance, IndexStatus card, knowledge_chunks table, knowledge_sources table (index state + job), KnowledgeChunkStore, KnowledgeEvaluationTest (retrieval evaluation), KnowledgeIndexProcessor (+9 more)

### Community 61 - "Instrument Prices"
Cohesion: 0.16
Nodes (3): PriceView, InstrumentPrice, InstrumentPriceRepository

### Community 62 - "Citation Validator"
Cohesion: 0.17
Nodes (3): CitationValidator, Result, CitationValidatorTest

### Community 63 - "Ollama AI Config"
Cohesion: 0.11
Nodes (20): ollama, model, options, batch-size, dimensions, max-retries, model, options (+12 more)

### Community 64 - "Knowledge Chunking Config"
Cohesion: 0.10
Nodes (20): max-context-chars, model-label, timeout, max-tokens, overlap-tokens, target-tokens, bilingual-mode, knowledge (+12 more)

### Community 65 - "App Storage & Brokerage Config"
Cohesion: 0.11
Nodes (20): domains, name, allowed-origins, max-size, storage, concentration-threshold-pct, evidence-per-holding, market-context-results (+12 more)

### Community 66 - "Docker Postgres Setup"
Cohesion: 0.10
Nodes (19): POSTGRES_DB, POSTGRES_PASSWORD, POSTGRES_USER, interval, retries, test, timeout, name (+11 more)

### Community 69 - "Research Ingestion Persistence"
Cohesion: 0.23
Nodes (6): Result, Block, Link, ResearchSource, ExtractedItem, ResearchItemExtractor

### Community 70 - "Text Cleaner"
Cohesion: 0.21
Nodes (3): TextCleaner, TickerDetector, Keywords

### Community 72 - "Agent Operating Rules"
Cohesion: 0.16
Nodes (14): AGENTS.md operating guide, Before you finish checklist, Known pitfalls (docs/failures), ArchitectureTest, Backend notes (Spring Boot 4.1), Persistence conventions, Testcontainers integration tests, Boot 4 / Jackson 3 / Testcontainers 2 APIs (+6 more)

### Community 74 - "Email Properties & CORS"
Cohesion: 0.18
Nodes (6): CorsProperties, EmailProperties, Storage, PortfolioProperties, Brokerage, ResearchProperties

### Community 75 - "Email Parsing Status"
Cohesion: 0.26
Nodes (6): EmailContent, ParsingStatus, FAILED, PARSED, PARTIAL, EmailTextExtractorTest

### Community 76 - "Link Classifier"
Cohesion: 0.20
Nodes (3): Classification, LinkClassifier, LinkClassifierTest

### Community 77 - "Datasource Config"
Cohesion: 0.11
Nodes (18): name, password, url, username, enabled, locations, ddl-auto, hibernate (+10 more)

### Community 79 - "Dev Profile Config"
Cohesion: 0.12
Nodes (16): import, allowed-origins, password, url, username, console, investary, cors (+8 more)

### Community 81 - "Portfolio Transactions Service"
Cohesion: 0.27
Nodes (3): TransactionCommand, PortfolioService, PortfolioTransactionRepository

### Community 86 - "Index Version"
Cohesion: 0.25
Nodes (8): IndexVersion, Assistant, Chunking, Email, KnowledgeProperties, Pdf, Search, Worker

### Community 88 - "Instrument Entity"
Cohesion: 0.21
Nodes (9): DocumentService, DownloadJobWorker, Report download flow, ReportDownloader, RestrictedAddressDnsResolver, UrlPolicy, PdfTextExtractor, Brokerages config (TCBS, SSI, VNDIRECT, VCBS) (+1 more)

### Community 89 - "Report Type & Upload Metadata"
Cohesion: 0.16
Nodes (9): UploadMetadataRequest, ReportType, ETF_RESEARCH, INDUSTRY_ANALYSIS, MACROECONOMIC, MARKET_OVERVIEW, OTHER, STOCK_ANALYSIS (+1 more)

### Community 90 - "Download Request Service"
Cohesion: 0.41
Nodes (6): DownloadRequestService, ResearchIngestionService, ResearchQueryService, DownloadJobRepository, ResearchItemRepository, SourceLinkRepository

### Community 92 - "Logging Config"
Cohesion: 0.15
Nodes (12): web, include, console, logging, structured, management, endpoints, server (+4 more)

### Community 93 - "Download Policy Config"
Cohesion: 0.15
Nodes (13): additional-allowed-domains, allow-any-port, allow-http, allow-private-networks, connect-timeout, max-attempts, max-duration, max-redirects (+5 more)

### Community 94 - "Email Inbox UI"
Cohesion: 0.27
Nodes (3): EmailDetail, EmailSummary, EmailInbox

### Community 95 - "Email Text Extractor"
Cohesion: 0.24
Nodes (5): ContentBlock, EmailTextExtractor, BilingualMode, ALL, PRIMARY_ONLY

### Community 96 - "Knowledge Controller"
Cohesion: 0.36
Nodes (4): JobResponse, KnowledgeController, ReindexRequest, ReindexResponse

### Community 98 - "DNS Resolver Tests"
Cohesion: 0.26
Nodes (3): RestrictedAddressDnsResolverTest, DnsResolver, DnsResolver

### Community 99 - "AI Knowledge Rules"
Cohesion: 0.18
Nodes (8): IndexVersion.PIPELINE, FakeAiConfiguration, ResearchItemExtractor, EmbeddingService, FakeEmbeddingModel, PromptBuilder, ScriptedChatModel, Deterministic research items (RULES_V1)

### Community 101 - "Download Jobs & Index ADRs"
Cohesion: 0.22
Nodes (9): Database-backed download jobs (SKIP LOCKED, leases, backoff), ADR 0005 Spring AI for models, own pgvector schema, Index version (embedding model + dimensions), LangChain4j (rejected), Failure 0004 Download worker polling after test container stopped, @DirtiesContext for tests enabling download worker, Failure 0007 Java text-block SQL losing trailing spaces, KnowledgeIndexQueue.requeue (+1 more)

### Community 103 - "Link Category"
Cohesion: 0.20
Nodes (7): LinkCategory, MARKET_REPORT, NAVIGATION, OTHER, PROMOTION, RESEARCH_REPORT, STOCK_RESEARCH

### Community 105 - "Duplicate & EML ADRs"
Cohesion: 0.24
Nodes (7): ADR 0002 Duplicate document handling, ADR 0003 Preserve original EML files, Derived email data (text, HTML, links, research items), Content policy (size cap, deadline, PDF signature, dedup), Failure 0005 Real brokerage emails contain personal data, scripts/check-harness.mjs tracked-file checks, Synthetic EML fixtures (tcbs-style-newsletter.eml, TestEmails)

### Community 106 - "Phase 2 Email Plan"
Cohesion: 0.24
Nodes (9): LinkClassifier, Phase 2 implementation plan, Real TCBS newsletter findings, V2__email_ingestion schema, V4 investor/portfolio and V5 intelligence schema, Click tracker unwrapping (Amazon SES), Flyway migrations V1-V5, Phase 2: email research ingestion (+1 more)

### Community 107 - "Angular App Shell"
Cohesion: 0.27
Nodes (4): App, appConfig, routes, Shell

### Community 109 - "Frontend Conventions"
Cohesion: 0.25
Nodes (7): HtmlSanitizer, Angular SPA frontend, Angular conventions (standalone, signals, zoneless), answerSegments(), Frontend notes (Angular 22), Vitest fake timers pitfall, Investary frontend

### Community 110 - "Link Status"
Cohesion: 0.22
Nodes (7): LinkStatus, DISCOVERED, DOWNLOADED, DOWNLOADING, FAILED, READY, SKIPPED

### Community 111 - "Worker Config"
Cohesion: 0.31
Nodes (9): worker, worker, concurrency, enabled, lease, max-attempts, poll-interval, reconcile-interval (+1 more)

### Community 112 - "Modular Monolith ADRs"
Cohesion: 0.31
Nodes (6): ADR 0001 Modular monolith, Module boundaries enforced by package convention, ADR 0008 Read-only portfolio intelligence, ArchitectureTest, Immutable assessment snapshots with holdings/evidence hashes, Explicit portfolio (PORTFOLIO_REQUIRED)

### Community 116 - "Reindex Scope"
Cohesion: 0.29
Nodes (5): ReindexScope, ALL, FAILED, MISSING, STALE

### Community 118 - "SSRF Download ADR"
Cohesion: 0.36
Nodes (7): ADR 0004 Report download policy and tracking-link unwrapping, Connection-time address policy (DNS resolver rejecting private/loopback), SSRF threat, URL policy (https, allowlist, no credentials, standard ports, max 5 redirects), Failure 0002 Spring Boot 4 / Jackson 3 / Testcontainers 2 package moves, ReportDownloader, Spring Boot 4 / Jackson 3 / Testcontainers 2 package moves

### Community 119 - "Citation Integrity ADR"
Cohesion: 0.36
Nodes (4): Hybrid retrieval SQL (pgvector + full text + tickers), ADR 0006 Citation integrity and evidence states, CitationValidator (can only lower evidence status), Evidence states SUPPORTED / PARTIAL / INSUFFICIENT

### Community 124 - "AI Retry Config"
Cohesion: 0.33
Nodes (6): model, retry, chat, embedding, max-attempts, ai

### Community 125 - "Portfolio Accounting ADR"
Cohesion: 0.53
Nodes (4): ADR 0007 Portfolio accounting, BigDecimal DECIMAL128, display rounding HALF_UP once, Chronological replay under portfolio lock (INSUFFICIENT_HOLDINGS), HoldingCalculator / PortfolioValuator

### Community 126 - "Toolchain Failure Note"
Cohesion: 0.40
Nodes (6): Failure 0001 Toolchain traps (Maven mirror, JDK 17, npx), maven-enforcer-plugin requiring Java 21, Maven wrapper (./mvnw) instead of mvn, Blocked npx (NVM4306), Failure 0008 IntelliJ dev-up run configuration starts nothing, Before-launch shell config must run as process with absolute PowerShell path

### Community 128 - "Pagination Config"
Cohesion: 0.50
Nodes (4): web, max-page-size, data, pageable

### Community 129 - "Module Cycle Failure"
Cohesion: 0.50
Nodes (4): Deterministic tracking-link unwrapping (Amazon SES L0), Failure 0003 Module dependency cycle email/research, email -> research module cycle via LinkClassifier/TrackingLinkUnwrapper, common.web.UrlCodec / common.util.Hashing extraction

### Community 130 - "Market Date Failure"
Cohesion: 0.67
Nodes (4): Manual price observations with source and date, Failure 0009 Market date vs JVM time zone, Market date via Clock with Asia/Ho_Chi_Minh (not @PastOrPresent), marketToday() frontend helper

## Knowledge Gaps
- **185 isolated node(s):** `com.investary:investary-backend`, `VALIDATION_FAILED`, `INVALID_DOCUMENT`, `DOCUMENT_NOT_FOUND`, `DUPLICATE_DOCUMENT` (+180 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 740 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **41 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `ImpactAssessmentService` connect `Intelligence Controller Wiring` to `pgVector & AI Errors`, `Chat Gateway & Events`, `Assessment Planning`, `Intelligence API Requests`, `REST Delete Endpoints`, `Impact Assessment Items`, `ArchitectureTest Module Rules`, `Portfolio Analysis Snapshot`, `Impact Assessment Service`, `Lifecycle Destroy Hooks`, `Index Version`, `Controller Tests & Enums`, `Download Request Service`?**
  _High betweenness centrality (0.018) - this node is a cross-community bridge._
- **What connects `com.investary:investary-backend`, `VALIDATION_FAILED`, `INVALID_DOCUMENT` to the rest of the system?**
  _185 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `ApiException Error Model` be split into smaller, more focused modules?**
  _Cohesion score 0.033176593521421105 - nodes in this community are weakly interconnected._
- **Why does `DocumentService` connect `Document Upload & Hashing` to `ApiException Error Model`, `Document Controller Tests`, `Chat Gateway & Events`, `Document & Email Controllers`, `Knowledge Status Queries`, `Intelligence Controller Wiring`, `Document Integration Tests`, `Intelligence Integration Test`, `Fake Embedding Test Support`, `Email Ingestion Integration Test`, `Impact Assessment Service`, `Document Service Operations`, `Text Extraction Results`, `Controller Tests & Enums`, `Download Request Service`, `AfterCommit Transactions`?**
  _High betweenness centrality (0.016) - this node is a cross-community bridge._
- **Should `Frontend Routes & Models` be split into smaller, more focused modules?**
  _Cohesion score 0.06843090082865544 - nodes in this community are weakly interconnected._
- **Why does `EmlParser` connect `EML Parser` to `Email Ingestion Integration Test`, `URL Codec`, `AfterCommit Transactions`, `Document Upload & Hashing`, `EML Test Fixtures`?**
  _High betweenness centrality (0.014) - this node is a cross-community bridge._
- **Should `Portfolio API Client` be split into smaller, more focused modules?**
  _Cohesion score 0.050957481337228175 - nodes in this community are weakly interconnected._