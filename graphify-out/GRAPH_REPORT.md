# Graph Report - investary  (2026-11-17)

## Corpus Check
- 319 files · ~231,387 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 6193 nodes · 19139 edges · 220 communities (162 shown, 58 thin omitted)
- Extraction: 92% EXTRACTED · 8% INFERRED · 0% AMBIGUOUS · INFERRED: 1511 edges (avg confidence: 0.85)
- Token cost: 336,954 input · 0 output

## Community Hubs (Navigation)
- Workers & Integration Tests
- Frontend App Shell & Automation API
- Automation & Portfolio Exceptions
- AI Errors & After-Commit Helpers
- Portfolio & Instrument API Client
- Gmail Query & Search Types
- Journal Frontend & API Client
- Email & Document API Client
- Test Mocking & Not-Found Errors
- Intelligence & Knowledge API Client
- Storage Errors & Test Fixtures
- Source Rules Backend
- Assistant Answers & Citations
- Frontend Feature Pages
- Brief Frontend & API Client
- Controller & Unit Tests
- Brief Store & Exceptions
- Runtime Settings
- Workflow Stages & Store
- Workflow Service & Retry
- Document Library Service
- Automation Overview & Health
- ErrorCode Catalogue
- Persistence & Domain Events
- Source Message Queries
- Link Classification
- Automation Job Queue & Worker
- REST Controllers (Workflow/Notify/Docs)
- Impact Assessment Service
- Paging & Document Responses
- Investor Goals API
- Angular Workspace Config
- Google & Settings Exceptions
- Portfolio Controller & Queries
- Investment Decision Aggregate
- Gmail Access Tokens
- Brief Evidence & Conflicts
- Decision Evidence
- Email Ingestion Tests
- Journal Exceptions
- Decision Portfolio Snapshot
- Job History & Workflow UI
- Source Rules UI
- Brief Controller & Queue
- Notification Service & Store
- Brief Composer & Validator
- Brief Job Processing
- Notifications UI
- Prompt Builders
- Intelligence Views
- Research Matcher
- Investor Profile Domain
- Instruments & Portfolio Commands
- Research Workflow Integration Test
- Gmail API Client
- Email Import & Download Events
- Notification Triggers & Prepare Stage
- Decision Snapshot Factory
- Google OAuth HTTP
- Impact Assessment Entity
- Decision Revisions & Status
- Portfolio Valuation & Money
- HTML Sanitizer & Link Unwrapping
- Brief Generator
- Brief Settings & Worker
- Research Assistant Service
- Local File Store
- Download Requests & Jobs
- Gmail Sync Integration Test
- Gmail Connection Controller
- Knowledge Base Integration Test
- Phase 2-4 Design Docs
- Frontend Package Manifest
- Gmail Sync Runs
- Workflow Policies & Failure Classifier
- HoldingCalculator Replay
- Brief Status & Events
- EML Parser & Hashing
- Portfolio Transactions
- PDF Text Extraction
- Research Item Extraction
- Brief Integration Test
- Decision Journal Integration Test
- Gmail OAuth Design (ADR 0009)
- AI Concurrency & Chat Gateway
- Notification Rules
- Gmail Message Store
- Knowledge Controller & Indexing
- Instrument Controller & Prices
- Harness Checks & Checklist
- Intelligence & Research Requests
- Architecture ADRs 0001-0008
- Gmail Sync Service & Scheduler
- Index Stage & Knowledge Worker
- Email Content Analyzer
- Decision Controller & Commands
- Citation Validator
- Knowledge Index Queue
- Research Items
- Report Downloader Errors
- Automation Center Integration Test
- Daily Brief Design (ADR 0011)
- Architecture Module Map
- Local-only Request Filter
- Decision Service & Evidence Resolver
- Application Config A
- AI Usage Reporting
- Token Cipher
- Email Storage & Controller Tests
- Application Bootstrap & Clock
- Decision Transaction Links
- Text Chunker
- Knowledge Properties & IndexVersion
- Application Config B
- Automation Settings UI
- Embedding Service & AI Purpose
- Report Downloader Tests
- Gmail Sync Runner
- Gmail Account Store
- Storage Properties
- Portfolio Analysis
- Brief Periods
- Fake Embedding Model
- Application Config C
- Application Config D
- Library & Accounting ADRs
- Settings & Notifications Design (ADR 0012)
- Docker Compose Postgres
- Source Attachments
- Ticker Detection & Query Analysis
- Agent Harness Docs
- Portfolio Assistant
- Impact Assessment Items
- Research Workflow Design (ADR 0010)
- Assess Stage & Automation Settings
- Global Exception Handler
- URL Codec & Blocked Addresses
- Decision Views & Actions
- Dev Profile Config
- Download Policy ADR & Failure Notes
- DNS Resolver Tests
- Decision Content & Types
- Hybrid Search
- Download Job Queue
- Restricted DNS Resolver
- Application Config E
- Application Config F
- URL Policy
- OAuth State Store
- Email Import & Source Types
- Research Controller
- Instrument Prices
- Decision Journal Design (ADR 0013)
- Email Detail UI
- Application Config G
- Research Assistant UI
- ApiException Base
- Knowledge Source Loader
- Email Text Extraction
- Application Config H
- Application Config I
- Document Upload Metadata
- Email Text Extractor Tests
- Content Analyzer Tests
- Reindex Scope
- Knowledge Source Status
- Research Query Service
- CORS & Web Config
- Email Exceptions
- Brokerage Registry
- Application Config J
- Journal & Portfolio Rules
- Failure Memory Index
- Notification Entity
- Knowledge Chunk Store
- Portfolio Valuator Tests
- Download Configuration
- Document Provenance
- Application Config K
- Automation & Notification Rules
- Gmail & Untrusted Input Rules
- PDF Signature Validation
- Brief Section Keys
- Upload Panel UI
- Research Workflow Rules
- Intelligence Exceptions
- Flyway Upgrade Test
- AI Usage UI
- Gmail Message Outcomes
- Workflow Triggers
- Email Import Panel UI
- AI & Knowledge Base Rules
- Brief Item Kinds
- Application Config L
- Real Email Handling Notes
- Toolchain Failure Notes
- Angular Zoneless Pitfalls
- Daily Brief Rules
- Settings Store
- Duplicate Document Errors
- Language Detector
- PgVector Helpers
- Synthetic Research Corpus
- README Overview
- Malformed Email Error
- Embedding Dimension Error
- Embedding Unavailable Error
- Extraction Error
- REST API & Error Format
- Brand Mark
- Community 213
- Community 215
- Community 216
- Community 217
- Community 219

## God Nodes (most connected - your core abstractions)
1. `errorMessage()` - 100 edges
2. `ErrorCode` - 81 edges
3. `@angular/core` - 78 edges
4. `@angular/common` - 68 edges
5. `DocumentService` - 67 edges
6. `PortfolioQueryService` - 67 edges
7. `AutomationJobQueue` - 60 edges
8. `ResearchWorkflowIntegrationTest` - 57 edges
9. `AutomationCenterIntegrationTest` - 55 edges
10. `EmailImportService` - 53 edges

## Surprising Connections (you probably didn't know these)
- `Portfolio rules (HoldingCalculator replay)` --semantically_similar_to--> `No fictional P&L`  [INFERRED] [semantically similar]
  AGENTS.md → docs/phase-4-implementation.md
- `Claim/lock token guard on job writes` --semantically_similar_to--> `download_jobs DB-backed queue (SKIP LOCKED, leases)`  [INFERRED] [semantically similar]
  backend/CLAUDE.md → README.md
- `Fake timers before whenStable hang` --semantically_similar_to--> `Vitest spec conventions (HttpTestingController, fake timers)`  [INFERRED] [semantically similar]
  docs/failures/0012-angular-spec-plain-object-mutation.md → frontend/CLAUDE.md
- `0012 Angular specs mutating plain objects do not re-render` --references--> `features/automation (AutomationCenter shell)`  [INFERRED]
  docs/failures/0012-angular-spec-plain-object-mutation.md → frontend/CLAUDE.md
- `ErrorSummaries / FailureClassifier` --implements--> `Research workflow rules`  [INFERRED]
  backend/CLAUDE.md → AGENTS.md

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Knowledge indexing pipeline** — docs_phase_3_implementation_knowledgeindexworker, docs_phase_3_implementation_knowledgeindexprocessor, docs_phase_3_implementation_pdftextextractor, docs_phase_3_implementation_emailtextextractor, docs_phase_3_implementation_textchunker, docs_phase_3_implementation_embeddingservice, docs_phase_3_implementation_knowledgechunkstore [EXTRACTED 1.00]
- **Citation integrity pipeline** — docs_adr_0006_citation_integrity_backend_owned_citation_identity, docs_adr_0006_citation_integrity_citationvalidator, docs_adr_0006_citation_integrity_evidence_states, docs_adr_0006_citation_integrity_system_message_instructions, docs_adr_0006_citation_integrity_third_party_opinion_attribution [EXTRACTED 1.00]
- **Portfolio accounting model** — docs_adr_0007_portfolio_accounting_transactions_source_of_truth, docs_adr_0007_portfolio_accounting_holdingcalculator, docs_adr_0007_portfolio_accounting_weighted_average_cost, docs_adr_0007_portfolio_accounting_chronological_replay, docs_adr_0007_portfolio_accounting_bigdecimal_decimal128, docs_adr_0007_portfolio_accounting_manual_prices [EXTRACTED 1.00]
- **Portfolio dashboard page composition (holdings, transactions, pricing, research insights)** — frontend_src_app_features_portfolio_portfolio_dashboard, frontend_src_app_features_portfolio_transaction_form, frontend_src_app_features_portfolio_research_insights, frontend_src_app_features_portfolio_portfolio_dashboard_holdings_table, frontend_src_app_features_portfolio_portfolio_dashboard_manual_price_form [EXTRACTED 1.00]
- **Layered defense for downloading untrusted email URLs** — docs_adr_0004_report_download_policy_user_initiated_downloads, docs_adr_0004_report_download_policy_tracking_link_unwrapping, docs_adr_0004_report_download_policy_url_policy, docs_adr_0004_report_download_policy_restricted_address_dns_resolver, docs_adr_0004_report_download_policy_content_policy [EXTRACTED 1.00]
- **Agent harness: guide, failure memory and checks** — agents_agents_md, agents_known_pitfalls, scripts_check_harness, agents_architecturetest, readme_agent_harness [EXTRACTED 1.00]
- **Token-guarded DB job queues** — readme_download_job_queue, backend_claude_automationjobqueue, backend_claude_gmailsyncrunner, backend_claude_brief_pipeline, backend_claude_token_guard [INFERRED 0.85]
- **Untrusted email content handling** — agents_untrusted_input_rules, readme_html_sanitization, readme_secure_report_downloads, frontend_claude_sandboxed_email_iframe [INFERRED 0.85]
- **SKIP LOCKED Job Queue Pattern (claim token, lease, idempotency key)** — docs_adr_0010_research_workflow_orchestration_automation_jobs_postgresql_job_queue, docs_adr_0011_daily_brief_in_intelligence_brief_jobs_sibling_job_queue, docs_phase_5_milestone_2_automationjobworker, docs_phase_5_milestone_2_job_idempotency_key, docs_architecture_report_download_flow [EXTRACTED 1.00]
- **Research Workflow Stage Chain** — docs_phase_5_milestone_2_workflow_stage_pipeline, docs_architecture_downloadrequestservice, docs_phase_4_implementation_impactassessmentservice, docs_phase_5_milestone_2_sourcerelevanceservice, docs_architecture_emailimportservice_importraw [EXTRACTED 1.00]
- **Read-only Portfolio Access via QueryServices** — docs_architecture_intelligence_module, docs_architecture_journal_module, docs_architecture_portfolioqueryservice, docs_architecture_investorqueryservice, docs_adr_0008_read_only_portfolio_intelligence_architecturetest [EXTRACTED 1.00]
- **Automation Center views** — frontend_src_app_features_automation_automation_overview, frontend_src_app_features_automation_source_rules, frontend_src_app_features_automation_job_history, frontend_src_app_features_automation_job_detail, frontend_src_app_features_automation_automation_settings, frontend_src_app_features_automation_ai_usage [INFERRED 0.85]
- **Decision journal lifecycle UI (draft, finalize, execute with snapshot, evidence, transactions)** — frontend_src_app_features_journal_decision_list, frontend_src_app_features_journal_decision_form, frontend_src_app_features_journal_decision_detail, frontend_src_app_features_journal_decision_detail_snapshot, frontend_src_app_features_journal_decision_detail_evidence, frontend_src_app_features_journal_decision_detail_linked_transactions, frontend_src_app_features_journal_decision_detail_history [EXTRACTED 1.00]
- **Citation-backed AI output rendering** — frontend_src_app_features_assistant_research_assistant_answer_turns, frontend_src_app_features_assistant_research_assistant_sources_panel, frontend_src_app_features_briefs_brief_detail_brief_sections, frontend_src_app_features_briefs_brief_detail_sources_panel, frontend_src_app_features_assistant_research_assistant_evidence_status_badge [INFERRED 0.85]

## Communities (220 total, 58 thin omitted)

### Community 0 - "Workers & Integration Tests"
Cohesion: 0.06
Nodes (8): DocumentService, KnowledgeIndexProcessor, SearchFilters, AutomationJobQueueTest, DocumentIntegrationTest, IntelligenceIntegrationTest, KnowledgeEvaluationTest, TestDatabase

### Community 1 - "Frontend App Shell & Automation API"
Cohesion: 0.04
Nodes (75): App, appConfig, routes, AUTOMATION_API, AutomationCenterApiService, GMAIL_API, GmailApiService, JOBS_API (+67 more)

### Community 2 - "Automation & Portfolio Exceptions"
Cohesion: 0.03
Nodes (33): AutomationExceptions, AutomationJobNotFoundException, BroadSourceRuleException, DuplicateSourceRuleException, GmailNotConfiguredException, GmailNotConnectedException, InvalidSourceRuleException, JobNotRetryableException (+25 more)

### Community 3 - "AI Errors & After-Commit Helpers"
Cohesion: 0.04
Nodes (17): ErrorSummaries, AfterCommit, AiConcurrencyLimits, AiExceptions, AiNotConfiguredException, AiProviderException, AiTimeoutException, ChatGateway (+9 more)

### Community 4 - "Portfolio & Instrument API Client"
Cohesion: 0.03
Nodes (53): INSTRUMENT_API, InstrumentApiService, INVESTOR_API, InvestorApiService, PORTFOLIO_API, PortfolioApiService, Allocation, AllocationBasis (+45 more)

### Community 5 - "Gmail Query & Search Types"
Cohesion: 0.06
Nodes (5): GmailQueryValidator, EvidenceSourceNotFoundException, SearchHit, SearchResult, Keywords

### Community 6 - "Journal Frontend & API Client"
Cohesion: 0.03
Nodes (53): JournalApiService, DECISION_STATUSES, DECISION_TYPE_LABELS, DECISION_TYPES, DecisionAction, DecisionContent, DecisionDetail, DecisionEvidence (+45 more)

### Community 7 - "Email & Document API Client"
Cohesion: 0.04
Nodes (60): RFC-9457, dependencies, @angular/common@^22.2.0, @angular/core@^22.2.0, @angular/forms@^22.2.0, @angular/platform-browser@^22.2.0, @angular/router@^22.2.0, rxjs@~7.8.0 (+52 more)

### Community 8 - "Test Mocking & Not-Found Errors"
Cohesion: 0.05
Nodes (4): InvalidDocumentException, EmailNotFoundException, KnowledgeJobNotFoundException, DownloadNotAllowedException

### Community 9 - "Intelligence & Knowledge API Client"
Cohesion: 0.04
Nodes (54): clean(), INTELLIGENCE_API, IntelligenceApiService, clean(), KNOWLEDGE_API, KnowledgeApiService, RESEARCH_API, ResearchApiService (+46 more)

### Community 10 - "Storage Errors & Test Fixtures"
Cohesion: 0.04
Nodes (4): StorageException, TestPdfs, TestEmails, TestKnowledgeProperties

### Community 11 - "Source Rules Backend"
Cohesion: 0.06
Nodes (15): SourceRuleController, SourceRuleRequest, RuleMatcher, SenderVerifier, Normalized, SourceRuleCommand, SourceRuleService, SourceRuleView (+7 more)

### Community 12 - "Assistant Answers & Citations"
Cohesion: 0.06
Nodes (3): DocumentNotFoundException, AssistantAnswer, Citation

### Community 13 - "Frontend Feature Pages"
Cohesion: 0.05
Nodes (10): errorMessage(), AutomationOverview, JobDetailPage, BriefList, Dashboard, DocumentDetail, DocumentLibrary, EmailInbox (+2 more)

### Community 14 - "Brief Frontend & API Client"
Cohesion: 0.05
Nodes (38): BRIEF_API, BriefApiService, Brief, BriefAiStatus, BriefCitation, BriefFilters, BriefItem, BriefItemKind (+30 more)

### Community 15 - "Controller & Unit Tests"
Cohesion: 0.06
Nodes (6): ArchitectureTest, GmailQueryValidatorTest, GoogleClientsTest, DocumentControllerTest, BriefValidatorTest, PortfolioControllerTest

### Community 16 - "Brief Store & Exceptions"
Cohesion: 0.05
Nodes (30): BriefExceptions, BriefJobNotFoundException, BriefJobNotRetryableException, BriefNotFoundException, BriefsDisabledException, TypeReference<List<Limitation>>, BriefCitation, BriefListItem (+22 more)

### Community 17 - "Runtime Settings"
Cohesion: 0.06
Nodes (18): AutomationCenterController, UpdateSettingsRequest, Cached, FieldView, RuntimeSettings, SectionView, SettingField, SettingsChangedEvent (+10 more)

### Community 18 - "Workflow Stages & Store"
Cohesion: 0.07
Nodes (19): DownloadStage, MatchStage, Done, NextJob, StageResult, StageUpdate, Wait, StageStatus (+11 more)

### Community 19 - "Workflow Service & Retry"
Cohesion: 0.07
Nodes (21): RetryPolicy, WorkflowService, AttemptView, JobDetailView, JobView, MatchView, StageView, WorkflowView (+13 more)

### Community 20 - "Document Library Service"
Cohesion: 0.07
Nodes (6): ContentInfo, UploadDocumentCommand, Document, DocumentRepository, DocumentStorage, DocumentServiceTest

### Community 21 - "Automation Overview & Health"
Cohesion: 0.07
Nodes (19): AutomationOverviewService, InvalidPeriodException, Metrics, OverviewView, ComponentHealth, GmailFacts, State, DEGRADED (+11 more)

### Community 22 - "ErrorCode Catalogue"
Cohesion: 0.03
Nodes (59): ErrorCode, AI_NOT_CONFIGURED, AI_PROVIDER_ERROR, AI_TIMEOUT, ASSESSMENT_NOT_FOUND, AUTOMATION_JOB_NOT_FOUND, BRIEF_JOB_NOT_FOUND, BRIEF_NOT_FOUND (+51 more)

### Community 23 - "Persistence & Domain Events"
Cohesion: 0.05
Nodes (9): TransactionSynchronization, DocumentDeletedEvent, EmailDeletionService, SenderSummary, SourceMessageRepository, Portfolio, PortfolioRepository, DocumentDeletionListener (+1 more)

### Community 24 - "Source Message Queries"
Cohesion: 0.08
Nodes (14): EmailQueryService, Original, Attachment, EmailDetail, EmailSummary, EmailViews, Sender, ParsingStatus (+6 more)

### Community 25 - "Link Classification"
Cohesion: 0.05
Nodes (18): Classification, LinkCategory, MARKET_REPORT, NAVIGATION, OTHER, PROMOTION, RESEARCH_REPORT, STOCK_RESEARCH (+10 more)

### Community 26 - "Automation Job Queue & Worker"
Cohesion: 0.09
Nodes (13): AutomationJobWorker, StageHandler, WorkflowJobProcessor, JobType, ASSESS, DOWNLOADS, INDEX, MATCH (+5 more)

### Community 27 - "REST Controllers (Workflow/Notify/Docs)"
Cohesion: 0.15
Nodes (3): NotificationController, WorkflowController, DocumentController

### Community 28 - "Impact Assessment Service"
Cohesion: 0.12
Nodes (11): AssessmentTrigger, Created, ImpactAssessmentService, Plan, SourceAssessment, Tier, DIRECT, MARKET (+3 more)

### Community 29 - "Paging & Document Responses"
Cohesion: 0.09
Nodes (7): PageResponse, DocumentResponse, DocumentDownload, EmailController, DecisionFilter, DecisionSummary, SourceLinkView

### Community 30 - "Investor Goals API"
Cohesion: 0.10
Nodes (13): GoalRequest, InvestorController, GoalCommand, GoalView, InvestorProfileService, InvestorQueryService, GoalPriority, HIGH (+5 more)

### Community 31 - "Angular Workspace Config"
Cohesion: 0.05
Nodes (43): build, serve, test, builder, configurations, defaultConfiguration, options, cli (+35 more)

### Community 32 - "Google & Settings Exceptions"
Cohesion: 0.06
Nodes (10): GoogleApiException, GoogleExceptions, GoogleTransientException, HistoryExpiredException, InvalidGrantException, MessageNotFoundException, InvalidSettingsException, SettingsConflictException (+2 more)

### Community 33 - "Portfolio Controller & Queries"
Cohesion: 0.09
Nodes (4): PortfolioAnswer, PortfolioController, PortfolioRequest, TransactionRequest

### Community 35 - "Gmail Access Tokens"
Cohesion: 0.08
Nodes (6): Cached, GmailAccessTokens, ReconnectRequiredException, Tokens, InvestorControllerTest, PortfolioIntegrationTest

### Community 36 - "Brief Evidence & Conflicts"
Cohesion: 0.09
Nodes (19): BriefConflicts, Rating, Stance, NEGATIVE, NEUTRAL, POSITIVE, View, AssessmentRef (+11 more)

### Community 37 - "Decision Evidence"
Cohesion: 0.06
Nodes (13): EvidenceCommand, EvidenceView, DecisionEvidence, EvidenceKind, CITATION, DOCUMENT, EMAIL, IMPACT_ASSESSMENT (+5 more)

### Community 38 - "Email Ingestion Tests"
Cohesion: 0.13
Nodes (3): EmailIngestionIntegrationTest, EmlParserTest, Builder

### Community 39 - "Journal Exceptions"
Cohesion: 0.06
Nodes (11): DecisionConflictException, DecisionNotFoundException, DuplicateEvidenceException, DuplicateTransactionLinkException, EvidenceNotFoundException, FieldLockedException, InvalidDecisionException, InvalidTransactionLinkException (+3 more)

### Community 41 - "Job History & Workflow UI"
Cohesion: 0.08
Nodes (11): WorkflowApiService, AutomationJob, AutomationJobStatus, ResearchWorkflow, WorkflowStageName, WorkflowStageView, JobHistory, EMPTY (+3 more)

### Community 42 - "Source Rules UI"
Cohesion: 0.09
Nodes (18): SourceRuleApiService, SourceRule, SourceRuleRequest, Job Filters (status, type, source, Gmail rule, dates), Broad / Public Mailbox Domain Warning, Brokerage Source Rules List, emptyDraft(), normalizeDomain() (+10 more)

### Community 43 - "Brief Controller & Queue"
Cohesion: 0.11
Nodes (12): BriefController, GenerateRequest, BriefService, BriefJobView, BriefJobStatus, FAILED, PENDING, RUNNING (+4 more)

### Community 44 - "Notification Service & Store"
Cohesion: 0.09
Nodes (16): NotificationService, NotificationView, NotificationPriority, HIGH, LOW, NORMAL, NotificationType, DAILY_BRIEF_READY (+8 more)

### Community 45 - "Brief Composer & Validator"
Cohesion: 0.16
Nodes (9): BriefComposer, Composition, BriefEvidence, BriefValidator, Review, BriefContent, BriefItem, BriefSection (+1 more)

### Community 46 - "Brief Job Processing"
Cohesion: 0.11
Nodes (11): Draft, BriefJobProcessor, Failure, BriefStats, BriefJobOutcome, CREATED, SKIPPED, UNCHANGED (+3 more)

### Community 47 - "Notifications UI"
Cohesion: 0.08
Nodes (10): NotificationApiService, notificationLink(), AppNotification, NotificationType, detail(), NotificationList, render(), NotificationBell (+2 more)

### Community 48 - "Prompt Builders"
Cohesion: 0.11
Nodes (7): BriefPrompts, HoldingPrompt, IntelligencePrompts, PortfolioContext, Citations, LabeledHit, PromptBuilder

### Community 49 - "Intelligence Views"
Cohesion: 0.07
Nodes (29): AssessmentItemView, AssessmentListItem, AssessmentSnapshot, AssessmentView, EvidenceReference, HoldingResearchView, IntelligenceViews, RelevantResearch (+21 more)

### Community 50 - "Research Matcher"
Cohesion: 0.12
Nodes (11): IntelligenceProperties, HoldingResearch, MatchedEvidence, MatchKind, DIRECT, MARKET_CONTEXT, MENTION, PortfolioResearch (+3 more)

### Community 51 - "Investor Profile Domain"
Cohesion: 0.08
Nodes (16): ProfileRequest, ProfileCommand, ExperienceLevel, ADVANCED, BEGINNER, INTERMEDIATE, NONE, InvestmentHorizon (+8 more)

### Community 52 - "Instruments & Portfolio Commands"
Cohesion: 0.07
Nodes (18): InstrumentCommand, PortfolioCommand, PortfolioCommands, PriceCommand, TransactionCommand, Exchange, HNX, HOSE (+10 more)

### Community 54 - "Gmail API Client"
Cohesion: 0.13
Nodes (13): GmailApiClient, Header, HistoryPage, HistoryRecord, MessageMetadata, MessagePage, MessageRef, Profile (+5 more)

### Community 55 - "Email Import & Download Events"
Cohesion: 0.11
Nodes (3): EmailImportService, ReportDownloadedEvent, DownloadJobProcessor

### Community 56 - "Notification Triggers & Prepare Stage"
Cohesion: 0.11
Nodes (7): NotificationTriggers, PrepareStage, SourceRef, WorkflowSources, PortfolioRelevance, RelevantHolding, SourceRelevanceService

### Community 57 - "Decision Snapshot Factory"
Cohesion: 0.11
Nodes (10): AllocationEntry, DecisionSnapshot, Goal, Holding, InvestorState, PortfolioState, Position, DecisionSnapshotFactory (+2 more)

### Community 58 - "Google OAuth HTTP"
Cohesion: 0.11
Nodes (5): GmailProperties, Sync, GoogleHttp, Response, GoogleOAuthClient

### Community 59 - "Impact Assessment Entity"
Cohesion: 0.09
Nodes (3): TypeReference<List<String>>, ImpactAssessment, ImpactAssessmentRepository

### Community 60 - "Decision Revisions & Status"
Cohesion: 0.07
Nodes (18): RevisionView, DecisionRevision, DecisionStatus, CANCELLED, DECIDED, DRAFT, EXECUTED, RevisionKind (+10 more)

### Community 61 - "Portfolio Valuation & Money"
Cohesion: 0.12
Nodes (20): Money, PortfolioValuator, Row, Valuation, Allocation, AllocationBasis, COST_BASIS, MARKET_VALUE (+12 more)

### Community 62 - "HTML Sanitizer & Link Unwrapping"
Cohesion: 0.08
Nodes (6): HtmlSanitizer, TextCleaner, TrackingLinkUnwrapper, Dependency, HtmlSanitizerTest, TrackingLinkUnwrapperTest

### Community 63 - "Brief Generator"
Cohesion: 0.18
Nodes (6): Conflict, BriefGenerator, Plan, RetryLater, PlanLine, BriefGeneratorTest

### Community 64 - "Brief Settings & Worker"
Cohesion: 0.09
Nodes (11): BriefJobWorker, BriefProperties, NoResearchMode, MINIMAL, SKIP, Schedule, Worker, BriefScheduler (+3 more)

### Community 65 - "Research Assistant Service"
Cohesion: 0.14
Nodes (4): ModelAnswer, ResearchAssistantService, ResearchControllerTest, ResearchAssistantServiceTest

### Community 66 - "Local File Store"
Cohesion: 0.13
Nodes (6): LocalFileStore, LocalFileSystemDocumentStorage, LocalFileSystemEmailStorage, LocalFileStoreTest, LocalFileSystemDocumentStorageTest, LocalFileSystemEmailStorageTest

### Community 67 - "Download Requests & Jobs"
Cohesion: 0.09
Nodes (9): DownloadRequestService, Download, DownloadJob, DownloadJobRepository, DownloadJobStatus, FAILED, PENDING, RUNNING (+1 more)

### Community 69 - "Gmail Connection Controller"
Cohesion: 0.10
Nodes (6): ConnectResponse, GmailIntegrationController, SyncStarted, GmailConnectionService, PendingAuthorization, AutomationControllersTest

### Community 70 - "Knowledge Base Integration Test"
Cohesion: 0.15
Nodes (3): KnowledgeBaseIntegrationTest, KnowledgeTestSupport, ScriptedChatModel

### Community 71 - "Phase 2-4 Design Docs"
Cohesion: 0.10
Nodes (24): EmailTextExtractor, EmbeddingService, HybridSearchService, IndexStatus card, knowledge_chunks table, knowledge_sources table (index state + job), KnowledgeChunkStore, KnowledgeEvaluationTest (retrieval evaluation) (+16 more)

### Community 72 - "Frontend Package Manifest"
Cohesion: 0.08
Nodes (29): @angular/compiler@^22.2.0, tslib@^2.3.0, devDependencies, @angular/build@^22.2.5, @angular/cli@^22.2.5, @angular/compiler-cli@^22.2.0, jsdom@^30.0.0, prettier@^3.8.1 (+21 more)

### Community 73 - "Gmail Sync Runs"
Cohesion: 0.11
Nodes (15): SyncMode, INCREMENTAL, INITIAL, RECOVERY, SyncRunStatus, FAILED, PARTIAL, RUNNING (+7 more)

### Community 74 - "Workflow Policies & Failure Classifier"
Cohesion: 0.09
Nodes (13): FailureClassifier, Failed, WorkflowStatusPolicy, WorkflowStage, COMPLETED, DOCUMENTS_PROCESSED, EMAIL_IMPORTED, EMAIL_PARSED (+5 more)

### Community 75 - "HoldingCalculator Replay"
Cohesion: 0.17
Nodes (5): HoldingCalculator, Oversell, Position, Trade, HoldingCalculatorTest

### Community 76 - "Brief Status & Events"
Cohesion: 0.12
Nodes (14): BriefGeneratedEvent, BriefStatus, COMPLETED, FAILED, PARTIAL, SKIPPED, BriefTrigger, MANUAL (+6 more)

### Community 77 - "EML Parser & Hashing"
Cohesion: 0.17
Nodes (6): Hashing, Bodies, EmlParser, Sender, ParsedAttachment, ParsedEmail

### Community 78 - "Portfolio Transactions"
Cohesion: 0.09
Nodes (7): TransactionView, PortfolioTransaction, TransactionType, BUY, OPENING_POSITION, SELL, PortfolioTransactionRepository

### Community 79 - "PDF Text Extraction"
Cohesion: 0.13
Nodes (9): ExtractedText, SkipReason, NO_TEXT, OCR_REQUIRED, PARSING_FAILED, PdfTextExtractor, TextSegment, PdfTextExtractorTest (+1 more)

### Community 80 - "Research Item Extraction"
Cohesion: 0.14
Nodes (9): ResearchIngestionService, Result, Block, Link, ResearchSource, ExtractedItem, ResearchItemExtractor, ResearchItemRepository (+1 more)

### Community 83 - "Gmail OAuth Design (ADR 0009)"
Cohesion: 0.13
Nodes (20): ADR 0009 Gmail OAuth and Synchronization, RECONNECT_REQUIRED Status, EmailImportService.importRaw, Gmail Integration and Sync Flow, Security Posture, email_source_rules Table, gmail_accounts Table, gmail_messages Table (+12 more)

### Community 84 - "AI Concurrency & Chat Gateway"
Cohesion: 0.09
Nodes (3): ResizableSemaphore, Reply, AiConcurrencyLimitsTest

### Community 85 - "Notification Rules"
Cohesion: 0.16
Nodes (5): NotificationRules, NewRow, ImpactAssessmentCreatedEvent, Item, NotificationRulesTest

### Community 87 - "Knowledge Controller & Indexing"
Cohesion: 0.18
Nodes (8): JobResponse, KnowledgeController, KnowledgeIndexService, KnowledgeJob, KnowledgeSourceType, DOCUMENT, EMAIL, KnowledgeControllerTest

### Community 88 - "Instrument Controller & Prices"
Cohesion: 0.15
Nodes (3): InstrumentController, InstrumentRequest, PriceRequest

### Community 89 - "Harness Checks & Checklist"
Cohesion: 0.11
Nodes (19): Before you finish checklist, IntelliJ run configurations (.run/), AGENT_DOCS, checkDocsDrift(), checkFailureMemory(), checkIgnoredSources(), checkMigrations(), checkSensitiveFiles() (+11 more)

### Community 90 - "Intelligence & Research Requests"
Cohesion: 0.12
Nodes (3): AskRequest, FilterRequest, IntelligenceController

### Community 91 - "Architecture ADRs 0001-0008"
Cohesion: 0.12
Nodes (16): ADR 0001 Modular monolith, Module boundaries enforced by package convention, ADR 0005 Spring AI for models, own pgvector schema, Hybrid retrieval SQL (pgvector + full text + tickers), Index version (embedding model + dimensions), LangChain4j (rejected), ADR 0006 Citation integrity and evidence states, CitationValidator (can only lower evidence status) (+8 more)

### Community 92 - "Gmail Sync Service & Scheduler"
Cohesion: 0.13
Nodes (6): GmailSyncScheduler, AccountView, GmailStatusView, GmailSyncService, SyncRunView, GmailScheduleTest

### Community 93 - "Index Stage & Knowledge Worker"
Cohesion: 0.13
Nodes (4): IndexStage, Target, EmbeddingService, KnowledgeIndexWorker

### Community 94 - "Email Content Analyzer"
Cohesion: 0.21
Nodes (5): AnalyzedContent, ContentAnalyzer, MutableLink, State, ExtractedLink

### Community 95 - "Decision Controller & Commands"
Cohesion: 0.17
Nodes (9): DecisionController, DecisionRequest, EvidenceRequest, LinkRequest, TransitionRequest, DecisionCommands, LinkCommand, TransitionCommand (+1 more)

### Community 96 - "Citation Validator"
Cohesion: 0.14
Nodes (7): CitationValidator, Result, EvidenceStatus, INSUFFICIENT, PARTIAL, SUPPORTED, CitationValidatorTest

### Community 98 - "Research Items"
Cohesion: 0.09
Nodes (8): ResearchItemView, ResearchItem, ResearchItemType, COMPANY_NEWS, INDUSTRY_UPDATE, MACRO_UPDATE, MARKET_UPDATE, STOCK_RECOMMENDATION

### Community 99 - "Report Downloader Errors"
Cohesion: 0.19
Nodes (10): DownloadException, Kind, BLOCKED, PERMANENT, TRANSIENT, Done, DownloadedFile, Redirect (+2 more)

### Community 101 - "Daily Brief Design (ADR 0011)"
Cohesion: 0.14
Nodes (19): ADR 0011 Daily Briefs in Intelligence Module, CitationValidator, Daily Investment Brief Flow, Flyway-owned Schema, Investary Architecture, Knowledge Base and Assistant Pipeline, Module Layering (api/application/domain/persistence/storage), Brief Item Kinds (PORTFOLIO_FACT, SOURCE_CLAIM, AI_INTERPRETATION) (+11 more)

### Community 102 - "Architecture Module Map"
Cohesion: 0.16
Nodes (20): common Module, document Module, DocumentDeletedEvent, email Module, intelligence Module, investor Module, InvestorQueryService, journal Module (+12 more)

### Community 104 - "Decision Service & Evidence Resolver"
Cohesion: 0.21
Nodes (10): DecisionQueryService, DecisionService, EvidenceResolver, Resolved, DecisionEvidenceRepository, DecisionRevisionRepository, DecisionSnapshotRepository, DecisionTransactionLinkRepository (+2 more)

### Community 105 - "Application Config A"
Cohesion: 0.09
Nodes (23): max-concurrent-chat, max-concurrent-embedding, domains, name, allowed-origins, max-size, storage, concentration-threshold-pct (+15 more)

### Community 106 - "AI Usage Reporting"
Cohesion: 0.15
Nodes (7): AiUsageReportService, Bucket, BucketBuilder, InvalidUsageRequestException, Limits, PurposeTotal, UsageView

### Community 107 - "Token Cipher"
Cohesion: 0.17
Nodes (3): TokenCipher, TokenUnreadableException, TokenCipherTest

### Community 109 - "Application Bootstrap & Clock"
Cohesion: 0.13
Nodes (4): InvestaryApplication, ClockConfiguration, MutableClock, FakeAiConfiguration

### Community 111 - "Text Chunker"
Cohesion: 0.20
Nodes (4): Chunk, Piece, TextChunker, TextChunkerTest

### Community 112 - "Knowledge Properties & IndexVersion"
Cohesion: 0.16
Nodes (12): IndexVersion, Assistant, BilingualMode, ALL, PRIMARY_ONLY, Chunking, Email, Embedding (+4 more)

### Community 113 - "Application Config B"
Cohesion: 0.09
Nodes (22): name, web, password, url, username, enabled, locations, ddl-auto (+14 more)

### Community 114 - "Automation Settings UI"
Cohesion: 0.14
Nodes (7): SettingField, AutomationSettingsPage, normalize(), GMAIL, PROCESSING, state(), validateSection()

### Community 115 - "Embedding Service & AI Purpose"
Cohesion: 0.11
Nodes (9): Sleeper, AiPurpose, ASSESSMENT, BRIEF, INDEXING, OTHER, PORTFOLIO_ASSISTANT, RESEARCH_ASSISTANT (+1 more)

### Community 116 - "Report Downloader Tests"
Cohesion: 0.25
Nodes (3): DownloadProperties, Worker, ReportDownloaderTest

### Community 117 - "Gmail Sync Runner"
Cohesion: 0.32
Nodes (3): GmailSyncRunner, LockLostException, Run

### Community 119 - "Storage Properties"
Cohesion: 0.20
Nodes (6): DocumentStorageProperties, StorageProperties, EmailProperties, Storage, AiLimitsProperties, PortfolioProperties

### Community 120 - "Portfolio Analysis"
Cohesion: 0.18
Nodes (7): Observation, PortfolioAnalysis, ResearchCoverage, PortfolioAnalysisService, ProfileView, HoldingView, IntelligenceControllerTest

### Community 121 - "Brief Periods"
Cohesion: 0.19
Nodes (4): BriefPeriods, InvalidPeriodException, Period, BriefPeriodsTest

### Community 123 - "Application Config C"
Cohesion: 0.11
Nodes (20): ollama, model, options, batch-size, dimensions, max-retries, model, options (+12 more)

### Community 124 - "Application Config D"
Cohesion: 0.10
Nodes (20): max-context-chars, model-label, timeout, max-tokens, overlap-tokens, target-tokens, bilingual-mode, knowledge (+12 more)

### Community 125 - "Library & Accounting ADRs"
Cohesion: 0.13
Nodes (15): ADR 0002 Duplicate document handling, ADR 0003 Preserve original EML files, Derived email data (text, HTML, links, research items), Content policy (size cap, deadline, PDF signature, dedup), ADR 0007 Portfolio accounting, BigDecimal DECIMAL128, display rounding HALF_UP once, Chronological replay under portfolio lock (INSUFFICIENT_HOLDINGS), HoldingCalculator / PortfolioValuator (+7 more)

### Community 126 - "Settings & Notifications Design (ADR 0012)"
Cohesion: 0.15
Nodes (16): ADR 0012 Runtime Setting Overrides and In-app Notifications, Configuration-only Kill Switches, SettingsContributor, Automation Center and Notifications Flow, automation Module, Job Idempotency Key, AI Limits Enforcement, Automation Center (+8 more)

### Community 127 - "Docker Compose Postgres"
Cohesion: 0.10
Nodes (19): POSTGRES_DB, POSTGRES_PASSWORD, POSTGRES_USER, interval, retries, test, timeout, name (+11 more)

### Community 129 - "Ticker Detection & Query Analysis"
Cohesion: 0.16
Nodes (4): TickerDetector, QueryAnalysis, TickerDetectorTest, QueryAnalysisTest

### Community 130 - "Agent Harness Docs"
Cohesion: 0.14
Nodes (12): AGENTS.md operating guide, ArchitectureTest, Toolchain constraints (mvnw, JDK 21, no npx), Backend notes (Spring Boot 4.1), Backend test conventions (WebMvcTest, Testcontainers, WireMock), Version-specific APIs (Boot 4 / Jackson 3 / Testcontainers 2), Built-in browser pane UI check, CLAUDE.md Claude Code notes (+4 more)

### Community 133 - "Research Workflow Design (ADR 0010)"
Cohesion: 0.18
Nodes (14): ADR 0010 Research Workflow Orchestration in PostgreSQL, Process-wide AI Concurrency Semaphores, AiConcurrencyLimits, Automated Research Processing Flow, DownloadRequestService, AI Cost Control Limits, AutomationJobWorker, Phase 5 Milestone 2 Automated Research Processing (+6 more)

### Community 134 - "Assess Stage & Automation Settings"
Cohesion: 0.15
Nodes (4): AutomationSettings, AssessStage, Worker, WorkflowProperties

### Community 137 - "Decision Views & Actions"
Cohesion: 0.12
Nodes (14): DecisionAction, AMEND, ATTACH_EVIDENCE, CANCEL, EDIT, EXECUTE, FINALIZE, LINK_TRANSACTION (+6 more)

### Community 138 - "Dev Profile Config"
Cohesion: 0.12
Nodes (16): import, allowed-origins, password, url, username, console, investary, cors (+8 more)

### Community 139 - "Download Policy ADR & Failure Notes"
Cohesion: 0.14
Nodes (15): ADR 0004 Report download policy and tracking-link unwrapping, Database-backed download jobs (SKIP LOCKED, leases, backoff), Connection-time address policy (DNS resolver rejecting private/loopback), SSRF threat, Deterministic tracking-link unwrapping (Amazon SES L0), URL policy (https, allowlist, no credentials, standard ports, max 5 redirects), Report Download Flow, Failure 0002 Spring Boot 4 / Jackson 3 / Testcontainers 2 package moves (+7 more)

### Community 140 - "DNS Resolver Tests"
Cohesion: 0.19
Nodes (3): RestrictedAddressDnsResolverTest, DnsResolver, DnsResolver

### Community 141 - "Decision Content & Types"
Cohesion: 0.15
Nodes (8): DecisionCommand, DecisionContent, DecisionType, BUY, HOLD, OBSERVE, REBALANCE, SELL

### Community 143 - "Download Job Queue"
Cohesion: 0.29
Nodes (3): ClaimedJob, DownloadJobQueue, DownloadJobQueueTest

### Community 145 - "Application Config E"
Cohesion: 0.12
Nodes (16): ai-timeout, catch-up-window, chunks-per-source, enabled, max-ai-briefs-per-day, max-attempts, max-briefs-per-run, max-context-chars (+8 more)

### Community 146 - "Application Config F"
Cohesion: 0.12
Nodes (16): api-base-url, auth-uri, client-id, client-secret, connect-timeout, max-retries, max-retry-wait, read-timeout (+8 more)

### Community 148 - "OAuth State Store"
Cohesion: 0.27
Nodes (4): Authorization, OAuthStateStore, Pending, OAuthStateStoreTest

### Community 149 - "Email Import & Source Types"
Cohesion: 0.17
Nodes (5): EmailImportedEvent, Analysis, SourceType, EML_UPLOAD, GMAIL

### Community 150 - "Research Controller"
Cohesion: 0.18
Nodes (9): AskRequest, ResearchController, SearchHitView, SearchRequest, SearchResponse, MatchType, LEXICAL, TICKER (+1 more)

### Community 152 - "Decision Journal Design (ADR 0013)"
Cohesion: 0.20
Nodes (11): ADR 0013 Investment Decision Journal Module, Decision Lifecycle, Decision Journal Flow, V8__daily_briefs.sql Migration, Post-finalization Amendment of Narrative Fields, decision_revisions, decision_transaction_links, Decision Types (BUY, SELL, HOLD, REBALANCE, OBSERVE) (+3 more)

### Community 154 - "Application Config G"
Cohesion: 0.14
Nodes (14): workflow, assess-market-context, auto-assess, download-wait-timeout, enabled, index-wait-timeout, max-attempts, max-auto-assessments-per-day (+6 more)

### Community 156 - "ApiException Base"
Cohesion: 0.24
Nodes (3): ApiException, GoalNotFoundException, LinkNotFoundException

### Community 157 - "Knowledge Source Loader"
Cohesion: 0.26
Nodes (5): SourceLoader, DateBasis, EMAIL_SENT, PUBLISHED, SourceText

### Community 158 - "Email Text Extraction"
Cohesion: 0.22
Nodes (3): ContentBlock, Excerpts, EmailTextExtractor

### Community 159 - "Application Config H"
Cohesion: 0.15
Nodes (12): web, include, console, logging, structured, management, endpoints, server (+4 more)

### Community 160 - "Application Config I"
Cohesion: 0.15
Nodes (13): additional-allowed-domains, allow-any-port, allow-http, allow-private-networks, connect-timeout, max-attempts, max-duration, max-redirects (+5 more)

### Community 161 - "Document Upload Metadata"
Cohesion: 0.18
Nodes (9): UploadMetadataRequest, ReportType, ETF_RESEARCH, INDUSTRY_ANALYSIS, MACROECONOMIC, MARKET_OVERVIEW, OTHER, STOCK_ANALYSIS (+1 more)

### Community 164 - "Reindex Scope"
Cohesion: 0.20
Nodes (7): ReindexRequest, ReindexResponse, ReindexScope, ALL, FAILED, MISSING, STALE

### Community 165 - "Knowledge Source Status"
Cohesion: 0.20
Nodes (8): KnowledgeSourceStatus, FAILED, INDEXED, PENDING, PROCESSING, SKIPPED, KnowledgeStatus, Model

### Community 168 - "Email Exceptions"
Cohesion: 0.18
Nodes (4): DuplicateEmailException, EmailExceptions, EmailTooLargeException, InvalidEmailException

### Community 169 - "Brokerage Registry"
Cohesion: 0.25
Nodes (3): BrokerageRegistry, Brokerage, ResearchProperties

### Community 170 - "Application Config J"
Cohesion: 0.36
Nodes (11): worker, worker, worker, concurrency, enabled, lease, max-attempts, poll-interval (+3 more)

### Community 171 - "Journal & Portfolio Rules"
Cohesion: 0.20
Nodes (7): Append-only trigger on decision_revisions / snapshots, DecisionService (version check, revisions), DecisionSnapshotFactory, HoldingCalculator (DECIMAL128), features/journal (decision list/form/detail), Decision journal (Phase 6 M1), Portfolios and portfolio intelligence (Phase 4)

### Community 172 - "Failure Memory Index"
Cohesion: 0.20
Nodes (8): Known pitfalls (docs/failures index), ErrorSummaries / FailureClassifier, Java text blocks strip trailing spaces, 0010 gitignore storage/ hid Java storage packages, LocalFileStore / storage packages, 0011 Shell heredoc dropped Java regex escapes, WorkflowPoliciesTest, Testing suites

### Community 174 - "Notification Entity"
Cohesion: 0.20
Nodes (9): NotificationEntity, ASSESSMENT, BRIEF, BRIEF_JOB, DOCUMENT, EMAIL, GMAIL, JOB (+1 more)

### Community 178 - "Document Provenance"
Cohesion: 0.27
Nodes (5): Association, ProvenanceRepository, RelationshipType, ATTACHMENT, LINKED_REPORT

### Community 179 - "Application Config K"
Cohesion: 0.20
Nodes (10): sync, initial-delay, initial-lookback-days, interval, lock-lease, max-messages-per-run, page-size, recovery-max-lookback-days (+2 more)

### Community 180 - "Automation & Notification Rules"
Cohesion: 0.25
Nodes (8): AfterCommit.run, NotificationRules / NotificationTriggers / NotificationService, Persistence conventions (UUID ids, jsonb, CHECK enums), RuntimeSettings + SettingsContributor, features/automation (AutomationCenter shell), Automation Center and notifications (Phase 5 M4), Configuration (env overrides), Runtime settings (app_settings overrides)

### Community 181 - "Gmail & Untrusted Input Rules"
Cohesion: 0.22
Nodes (6): EML import workflow (Phase 2), Gmail sync (Phase 5 M1), Sanitized HTML preview (jsoup + CSP), Rule-based link classifier, Deterministic research item extraction (RULES_V1), Click tracker unwrapping (Amazon SES)

### Community 183 - "Brief Section Keys"
Cohesion: 0.22
Nodes (8): BriefSectionKey, EXECUTIVE_SUMMARY, FOLLOW_UPS, MARKET_OVERVIEW, OPPORTUNITIES, PORTFOLIO_UPDATES, RISKS_AND_CONFLICTS, SOURCES_AND_LIMITATIONS

### Community 185 - "Research Workflow Rules"
Cohesion: 0.29
Nodes (6): AutomationJobQueue, GmailSyncRunner, Workflow engine (StageHandler, WorkflowJobProcessor, WorkflowService), download_jobs DB-backed queue (SKIP LOCKED, leases), Automated research processing (Phase 5 M2), Secure report downloads (URL policy, SSRF checks)

### Community 187 - "Intelligence Exceptions"
Cohesion: 0.25
Nodes (3): AssessmentNotFoundException, IntelligenceExceptions, PortfolioRequiredException

### Community 190 - "Gmail Message Outcomes"
Cohesion: 0.29
Nodes (5): MessageOutcome, DUPLICATE, IMPORTED, NOT_IMPORTED, REJECTED

### Community 191 - "Workflow Triggers"
Cohesion: 0.29
Nodes (7): WorkflowTrigger, DOCUMENT_UPLOAD, EMAIL_IMPORT, GMAIL, MANUAL, RECONCILE, REPORT_DOWNLOADED

### Community 193 - "AI & Knowledge Base Rules"
Cohesion: 0.33
Nodes (5): FakeAiConfiguration, FakeEmbeddingModel, ScriptedChatModel, answerSegments() citation rendering, Research assistant / RAG (Phase 3)

### Community 194 - "Brief Item Kinds"
Cohesion: 0.33
Nodes (5): BriefItemKind, AI_INTERPRETATION, PORTFOLIO_FACT, SOURCE_CLAIM, SYSTEM_NOTE

### Community 195 - "Application Config L"
Cohesion: 0.33
Nodes (6): model, retry, chat, embedding, max-attempts, ai

### Community 196 - "Real Email Handling Notes"
Cohesion: 0.33
Nodes (4): common.storage.LocalFileStore, Phase 2 implementation plan, Real TCBS newsletter findings, V2__email_ingestion schema

### Community 197 - "Toolchain Failure Notes"
Cohesion: 0.40
Nodes (6): Failure 0001 Toolchain traps (Maven mirror, JDK 17, npx), maven-enforcer-plugin requiring Java 21, Maven wrapper (./mvnw) instead of mvn, Blocked npx (NVM4306), Failure 0008 IntelliJ dev-up run configuration starts nothing, Before-launch shell config must run as process with absolute PowerShell path

### Community 198 - "Angular Zoneless Pitfalls"
Cohesion: 0.47
Nodes (6): 0012 Angular specs mutating plain objects do not re-render, Fake timers before whenStable hang, Zoneless change detection (dirty marking), Angular conventions (standalone, signals, zoneless), Frontend notes (Angular 22), Vitest spec conventions (HttpTestingController, fake timers)

### Community 199 - "Daily Brief Rules"
Cohesion: 0.40
Nodes (4): AiConcurrencyLimits, Brief pipeline (BriefScheduler, BriefGenerator, BriefValidator, BriefPeriods), ChatGateway, Daily investment brief (Phase 5 M3)

### Community 205 - "README Overview"
Cohesion: 0.40
Nodes (5): Investary frontend, Investary (README), Known limitations, Local single-user, no authentication, Next phases roadmap

## Knowledge Gaps
- **439 isolated node(s):** `RFC-9457`, `BUY`, `OPENING_POSITION`, `SELL`, `MARKET_REPORT` (+434 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 1457 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **58 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Decision Evidence` connect `Email & Document API Client` to `Decision Journal Design (ADR 0013)`, `Journal Frontend & API Client`, `Phase 2-4 Design Docs`?**
  _High betweenness centrality (0.015) - this node is a cross-community bridge._
- **What connects `RFC-9457`, `BUY`, `OPENING_POSITION` to the rest of the system?**
  _439 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Workers & Integration Tests` be split into smaller, more focused modules?**
  _Cohesion score 0.061303581914268934 - nodes in this community are weakly interconnected._
- **Why does `ImpactAssessmentService` connect `Phase 2-4 Design Docs` to `Daily Brief Design (ADR 0011)`, `Research Workflow Design (ADR 0010)`, `Architecture Module Map`, `Email & Document API Client`, `Decision Journal Design (ADR 0013)`, `Settings & Notifications Design (ADR 0012)`?**
  _High betweenness centrality (0.012) - this node is a cross-community bridge._
- **Should `Frontend App Shell & Automation API` be split into smaller, more focused modules?**
  _Cohesion score 0.039739173228346455 - nodes in this community are weakly interconnected._
- **Why does `PortfolioQueryService` connect `Brief Settings & Worker` to `Automation & Portfolio Exceptions`, `AI Errors & After-Commit Helpers`, `Portfolio Assistant`, `Gmail Query & Search Types`, `Test Mocking & Not-Found Errors`, `Source Rules Backend`, `Assistant Answers & Citations`, `Controller & Unit Tests`, `Brief Store & Exceptions`, `REST Controllers (Workflow/Notify/Docs)`, `Impact Assessment Service`, `Portfolio Controller & Queries`, `Brief Evidence & Conflicts`, `Journal Exceptions`, `Brief Controller & Queue`, `Research Matcher`, `Notification Triggers & Prepare Stage`, `Decision Snapshot Factory`, `Instrument Controller & Prices`, `Decision Service & Evidence Resolver`, `Portfolio Analysis`?**
  _High betweenness centrality (0.011) - this node is a cross-community bridge._
- **Should `Automation & Portfolio Exceptions` be split into smaller, more focused modules?**
  _Cohesion score 0.02787151606049244 - nodes in this community are weakly interconnected._