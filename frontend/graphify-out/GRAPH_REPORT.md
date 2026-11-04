# Graph Report - frontend  (2026-11-03)

## Corpus Check
- Corpus is ~40,003 words - fits in a single context window. You may not need a graph.

## Summary
- 809 nodes · 2024 edges · 44 communities (25 shown, 19 thin omitted)
- Extraction: 100% EXTRACTED · 0% INFERRED · 0% AMBIGUOUS · INFERRED: 5 edges (avg confidence: 0.85)
- Token cost: 227,555 input · 0 output

## Community Hubs (Navigation)
- App Shell & Angular Core
- Research Assistant & Knowledge API
- Daily Briefs
- Angular Workspace Config
- npm Dependencies
- Notifications Bell
- Source Rules
- Portfolio Intelligence API
- Investor Profile & Goals
- Document API & Errors
- Automation Overview Models
- Transaction Form
- Automation & Gmail API
- Job History
- Dashboard & Research Insights
- Portfolio API
- Email Detail Page
- Email API & Paging
- Instrument & Price API
- Automation Settings Page
- Automation Overview Page
- Portfolio Dashboard
- Research Workflow API
- Transactions & Allocation Models
- Runtime Settings API
- Email API Service
- Investor API
- Research Item Labels
- Email Inbox
- Workflow Status Panel
- Brief List
- Upload Panel
- AI Usage Page
- Settings Spec Helpers
- Email Import Panel
- Job Detail Page
- Document Library
- Email Detail Spec
- File Size Pipe
- Portfolio List
- Document Detail
- Brand Mark & Favicon
- Frontend README
- Index Host Page

## God Nodes (most connected - your core abstractions)
1. `errorMessage()` - 91 edges
2. `@angular/core` - 70 edges
3. `@angular/common` - 62 edges
4. `Alert` - 42 edges
5. `@angular/router` - 38 edges
6. `EmptyState` - 24 edges
7. `PortfolioApiService` - 21 edges
8. `EmailApiService` - 20 edges
9. `InvestorProfilePage` - 20 edges
10. `StatusBadge` - 19 edges

## Surprising Connections (you probably didn't know these)
- `Automation settings template` --references--> `Alert`  [EXTRACTED]
  src/app/features/automation/automation-settings.html → src/app/shared/components/alert.ts
- `Upload Panel Template` --references--> `Alert`  [EXTRACTED]
  src/app/features/documents/upload-panel.html → src/app/shared/components/alert.ts
- `investor-profile.html (profile & goals forms)` --references--> `Alert`  [EXTRACTED]
  src/app/features/investor/investor-profile.html → src/app/shared/components/alert.ts
- `research-insights.html (relevant research, impact assessments)` --references--> `Alert`  [EXTRACTED]
  src/app/features/portfolio/research-insights.html → src/app/shared/components/alert.ts
- `transaction-form.html (add/edit transaction)` --references--> `Alert`  [EXTRACTED]
  src/app/features/portfolio/transaction-form.html → src/app/shared/components/alert.ts

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Automation Center pages** — src_app_features_automation_ai_usage_template, src_app_features_automation_automation_overview_template, src_app_features_automation_automation_settings_template, src_app_features_automation_job_detail_template, src_app_features_automation_job_history_template, src_app_features_automation_source_rules_template [EXTRACTED 1.00]
- **Paged list pages with pager and empty state** — src_app_features_briefs_brief_list_template, src_app_features_documents_document_library_template, src_app_features_emails_email_inbox_template [INFERRED 0.85]

## Communities (44 total, 19 thin omitted)

### Community 0 - "App Shell & Angular Core"
Cohesion: 0.06
Nodes (50): @angular/common, @angular/core, @angular/forms, @angular/router, App, appConfig, routes, PURPOSE_LABELS (+42 more)

### Community 1 - "Research Assistant & Knowledge API"
Cohesion: 0.06
Nodes (29): answerSegments() citation rendering, Frontend notes (Angular 22), clean(), KNOWLEDGE_API, KnowledgeApiService, RESEARCH_API, ResearchApiService, segment() (+21 more)

### Community 2 - "Daily Briefs"
Cohesion: 0.06
Nodes (29): BRIEF_API, BriefApiService, Brief, BriefAiStatus, BriefCitation, BriefFilters, BriefItem, BriefItemKind (+21 more)

### Community 3 - "Angular Workspace Config"
Cohesion: 0.05
Nodes (43): build, serve, test, builder, configurations, defaultConfiguration, options, cli (+35 more)

### Community 4 - "npm Dependencies"
Cohesion: 0.07
Nodes (37): dependencies, @angular/common@^22.2.0, @angular/compiler@^22.2.0, @angular/core@^22.2.0, @angular/forms@^22.2.0, @angular/platform-browser@^22.2.0, @angular/router@^22.2.0, rxjs@~7.8.0 (+29 more)

### Community 5 - "Notifications Bell"
Cohesion: 0.09
Nodes (8): NotificationApiService, notificationLink(), NOTIFICATIONS_API, AppNotification, NotificationType, NotificationList, Shell, NotificationBell

### Community 6 - "Source Rules"
Cohesion: 0.10
Nodes (11): SourceRuleApiService, SourceRule, SourceRuleRequest, emptyDraft(), normalizeDomain(), ruleRequest(), SourceRules, RULE (+3 more)

### Community 7 - "Portfolio Intelligence API"
Cohesion: 0.12
Nodes (17): clean(), INTELLIGENCE_API, IntelligenceApiService, AiStatus, Assessment, AssessmentListItem, HoldingResearch, IntelligenceFilters (+9 more)

### Community 8 - "Investor Profile & Goals"
Cohesion: 0.15
Nodes (10): ExperienceLevel, GoalPriority, InstrumentType, InvestmentHorizon, ProfileRequest, RiskTolerance, GoalDraft, InvestorProfilePage (+2 more)

### Community 9 - "Document API & Errors"
Cohesion: 0.19
Nodes (9): RFC-9457, rxjs, DocumentApiService, DOCUMENTS_API, ProblemDetail, REPORT_TYPES, ResearchDocument, UploadMetadata (+1 more)

### Community 10 - "Automation Overview Models"
Cohesion: 0.12
Nodes (15): AiUsage, AutomationJobType, ComponentHealth, GmailAccount, GmailAccountStatus, HealthState, JobAttempt, OverviewMetrics (+7 more)

### Community 11 - "Transaction Form"
Cohesion: 0.16
Nodes (6): TransactionType, Draft, marketToday(), fpt, transaction-form.html (add/edit transaction), TransactionForm

### Community 12 - "Automation & Gmail API"
Cohesion: 0.14
Nodes (9): AUTOMATION_API, GMAIL_API, GmailApiService, JOBS_API, SOURCES_API, WORKFLOWS_API, GmailStatus, JobDetail (+1 more)

### Community 13 - "Job History"
Cohesion: 0.19
Nodes (4): AutomationJob, AutomationJobStatus, JobFilters, JobHistory

### Community 14 - "Dashboard & Research Insights"
Cohesion: 0.15
Nodes (6): errorMessage(), EvidenceReference, ImpactCategory, Dashboard, ResearchInsights, research-insights.html (relevant research, impact assessments)

### Community 15 - "Portfolio API"
Cohesion: 0.15
Nodes (6): PortfolioApiService, Allocation, Portfolio, PortfolioRequest, allocation, portfolio

### Community 16 - "Email Detail Page"
Cohesion: 0.18
Nodes (3): ResearchItem, SourceLink, EmailDetail

### Community 17 - "Email API & Paging"
Cohesion: 0.18
Nodes (11): EMAILS_API, Page, EmailAttachment, EmailDetail, EmailQuery, EmailSummary, LinkCategory, LinkStatistics (+3 more)

### Community 18 - "Instrument & Price API"
Cohesion: 0.17
Nodes (8): INSTRUMENT_API, InstrumentApiService, INVESTOR_API, PORTFOLIO_API, Instrument, InstrumentRequest, PriceObservation, PriceRequest

### Community 19 - "Automation Settings Page"
Cohesion: 0.20
Nodes (6): SettingField, AutomationSettingsPage, normalize(), state(), Automation settings template, validateSection()

### Community 22 - "Research Workflow API"
Cohesion: 0.20
Nodes (5): WorkflowApiService, ResearchWorkflow, WorkflowStageView, stage(), workflow()

### Community 23 - "Transactions & Allocation Models"
Cohesion: 0.21
Nodes (8): AllocationBasis, AllocationBreakdown, AllocationSlice, Exchange, Page, Transaction, TransactionRequest, ValuationStatus

### Community 24 - "Runtime Settings API"
Cohesion: 0.29
Nodes (6): AutomationCenterApiService, SettingsSection, SettingValue, FALLBACK_ZONES, SECTION_TITLES, SectionState

### Community 26 - "Investor API"
Cohesion: 0.25
Nodes (4): InvestorApiService, Goal, GoalRequest, InvestorProfile

### Community 27 - "Research Item Labels"
Cohesion: 0.24
Nodes (7): ReportType, LinkedDocument, ResearchItemType, HIDDEN_BY_DEFAULT, ITEM_TYPE_LABELS, REPORT_TYPE_LABELS, ReportTypeLabelPipe

## Knowledge Gaps
- **5 isolated node(s):** `RFC-9457`, `Investary frontend README`, `index.html (app-root host)`, `Investary Favicon (green rounded square with white upward trend line)`, `.brand__mark (Shell brand mark)`
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 239 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **19 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `errorMessage()` connect `Dashboard & Research Insights` to `App Shell & Angular Core`, `Research Assistant & Knowledge API`, `Daily Briefs`, `Notifications Bell`, `Source Rules`, `Investor Profile & Goals`, `Document API & Errors`, `Transaction Form`, `Job History`, `Email Detail Page`, `Automation Settings Page`, `Automation Overview Page`, `Portfolio Dashboard`, `Runtime Settings API`, `Research Item Labels`, `Email Inbox`, `Workflow Status Panel`, `Brief List`, `Upload Panel`, `AI Usage Page`, `Email Import Panel`, `Job Detail Page`, `Document Library`, `Portfolio List`, `Document Detail`?**
  _High betweenness centrality (0.222) - this node is a cross-community bridge._
- **What connects `RFC-9457`, `Investary frontend README`, `index.html (app-root host)` to the rest of the system?**
  _5 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `App Shell & Angular Core` be split into smaller, more focused modules?**
  _Cohesion score 0.060570762958648806 - nodes in this community are weakly interconnected._
- **Why does `@angular/core` connect `App Shell & Angular Core` to `Research Assistant & Knowledge API`, `Daily Briefs`, `npm Dependencies`, `Notifications Bell`, `Source Rules`, `Portfolio Intelligence API`, `Investor Profile & Goals`, `Document API & Errors`, `Transaction Form`, `Automation & Gmail API`, `Portfolio API`, `Email API & Paging`, `Instrument & Price API`, `Research Workflow API`, `Runtime Settings API`, `Research Item Labels`, `Settings Spec Helpers`, `Email Detail Spec`, `File Size Pipe`?**
  _High betweenness centrality (0.145) - this node is a cross-community bridge._
- **Should `Research Assistant & Knowledge API` be split into smaller, more focused modules?**
  _Cohesion score 0.056265984654731455 - nodes in this community are weakly interconnected._
- **Why does `@angular/common` connect `App Shell & Angular Core` to `Research Assistant & Knowledge API`, `Daily Briefs`, `Settings Spec Helpers`, `npm Dependencies`, `Notifications Bell`, `Source Rules`, `Portfolio Intelligence API`, `Email Detail Spec`, `Document API & Errors`, `Investor Profile & Goals`, `Transaction Form`, `Automation & Gmail API`, `Portfolio API`, `Email API & Paging`, `Instrument & Price API`, `Research Workflow API`, `Runtime Settings API`, `Research Item Labels`?**
  _High betweenness centrality (0.117) - this node is a cross-community bridge._
- **Should `Daily Briefs` be split into smaller, more focused modules?**
  _Cohesion score 0.05827067669172932 - nodes in this community are weakly interconnected._