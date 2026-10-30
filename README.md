# BudgetWise 💰📊✨

BudgetWise is a modern, AI-powered personal finance application built with Next.js and Firebase. It empowers users to track expenses, manage budgets, visualize spending patterns, and receive intelligent insights—all in a secure, responsive interface. This project showcases full-stack development skills, including server-side rendering, real-time data handling, and seamless AI integration for enhanced user experience.

## Repository layout

- `apps/web/`: the Next.js web app and the `/api/v1` mobile API (deployed on Vercel). Run the npm commands below from here; paths such as `src/...` and `scripts/...` are relative to it.
- `apps/android/`: the Android app (Gradle project; run `./gradlew` from here).
- `brand/`: logo and icon sources for both apps. `docs/`: plans and specs.
- `.github/workflows/`: one CI pipeline per deployment.

See [docs/repo-layout.md](docs/repo-layout.md) for the CI pipelines, the secrets they need and the history of this layout.

## ✨ Key Features

- **Interactive Dashboard**: Real-time overview of financial health with overview cards, recent transactions, spending charts (using Recharts), and budget progress trackers. 📊
- **Smart Transaction Management**: Add, categorize, and track income/expenses with AI-assisted data extraction and categorization flows. 💳
- **Budget & Goal Setting**: Create and monitor category-based budgets with visual progress indicators and goal alerts. 🎯
- **AI-Powered Suggestions**: Get personalized spending recommendations, anomaly detection, and optimization tips via Genkit AI flows. 🧠🤖
- **Custom Categories & Icons**: Organize finances with user-defined categories and intuitive icon selection for better visualization. 🏷️
- **Secure User Authentication**: Robust signup/login with Firebase Auth, including protected routes and session management. 🔒
- **Guest Mode & Responsive UI**: Explore features without signup; fully responsive design works on desktop, tablet, and mobile. 📱💻
- **Theme Support**: Light/dark mode toggle for personalized experience. 🌙☀️

## 🏗️ Project Architecture & Skills Showcased

This application demonstrates advanced full-stack capabilities:

- **Next.js 15 App Router**: Organized routing with `(authenticated)` layout for protected pages, server actions for form handling (`actions.ts`, `db-actions.ts`), and middleware for auth checks (`src/middleware.ts`).
- **Firestore Data Layer**: Server-side Firestore access for users, budgets, categories, and transactions (`src/lib/firestore.ts`, `src/lib/users.ts`, `src/lib/budgets.ts`, `src/lib/mobile/`).
- **AI Integration with Genkit**: Custom flows for transaction data extraction, categorization, and spending suggestions (`src/ai/flows/`), powered by Google AI. Development server runs concurrently with the app for seamless testing.
- **State Management & Hooks**: Context providers for auth and theme (`src/context/`), custom hooks like `use-toast` for UI feedback.
- **UI Components**: Reusable Shadcn/UI components (built on Radix UI and Tailwind) for modals, charts, forms, and more, emphasizing accessible and customizable design.
- **Validation & Forms**: Zod schemas integrated with React Hook Form for secure, client/server-side validation.
- **Real-Time & Security**: Firebase for authentication, Firestore for data storage/retrieval, with admin SDK for server-side ops. Bcryptjs for password hashing and Jose for JWT handling.
- **Performance Optimizations**: Turbopack for dev, lazy loading, and efficient charting with Recharts.

The codebase follows best practices: clean separation of concerns, ESLint/Prettier linting, and scripts for seeding data (`scripts/seedCategories.ts`).

## 🚀 Tech Stack

### Frontend
- **Next.js 15.3.3** ⚛️: Server-side rendering, API routes, and static generation.
- **TypeScript 5.9.2** 📘: Type safety across the application.
- **Tailwind CSS 3.4.1** 🎨: Utility-first styling with animations.
- **Shadcn/UI** 🧩: Headless UI components (Accordion, Dialog, Chart, etc.) on Radix UI.
- **Recharts 2.15.1** 📈: Declarative charting for spending visualizations.
- **React Hook Form 7.54.2 + Zod 3.24.2** 📝: Form handling and validation.
- **Next Themes 0.3.0** 🌙: Theme management.
- **Lucide React 0.475.0** 🔍: Icons for UI elements.

### Backend & Database
- **Firebase 11.9.1 + Firebase Admin 12.2.0** 🔥: Authentication, Firestore for real-time data, and cloud functions compatibility.
- **Jose 5.6.3 + Bcryptjs 2.4.3** 🔑: JWT tokens and password security.

### AI & Integrations
- **Genkit 1.15.5** 🤖: AI framework with Google AI integration (`@genkit-ai/googleai`).
- **Date-fns 3.6.0** 📅: Date manipulation for transactions.

### Tools & Utils
- **Concurrently 9.2.0**: Parallel dev servers for Next.js and Genkit.
- **Class Variance Authority (CVA) + Clsx + Tailwind Merge**: Dynamic class composition for components.
- **Lodash 4.17.21**: Utility functions.

## 🛠️ Getting Started

### Prerequisites
- Node.js (v18 or higher)
- npm or pnpm
- Firebase project (for auth and database)

### Installation
1. **Clone the repository:**
   ```bash
   git clone https://github.com/codes71/BudgetWise.git
   cd BudgetWise/apps/web
   ```
2. **Install dependencies:**
   ```bash
   npm install
   ```

### Environment Variables
All data lives in Cloud Firestore, accessed on the server with `firebase-admin` (`src/lib/firestore.ts`). Create a `.env.local` file in `apps/web/` (it is git-ignored) with placeholder values replaced:
```
# Firestore, option A: service account fields (what Vercel uses)
FIREBASE_PROJECT_ID=your-project-id
FIREBASE_CLIENT_EMAIL=firebase-adminsdk-xxxxx@your-project-id.iam.gserviceaccount.com
FIREBASE_PRIVATE_KEY="-----BEGIN PRIVATE KEY-----\n...\n-----END PRIVATE KEY-----\n"

# Firestore, option B: path to a service account key file instead of the three above
# GOOGLE_APPLICATION_CREDENTIALS=/absolute/path/to/service-account.json

# Firestore, option C: the local emulator (see "Firestore emulator" below)
# FIRESTORE_EMULATOR_HOST=127.0.0.1:8085
# FIREBASE_PROJECT_ID=demo-budgetwise

JWT_SECRET=at-least-32-characters-long (web sessions and the mobile API under /api/v1)
ANTHROPIC_API_KEY=your-anthropic-api-key (optional, only for the AI features)
OPENAI_API_KEY=your-openai-api-key (optional, the fallback when Claude is down)
# OPENAI_MODEL=gpt-6.1-sol (optional, the fallback model)
```
- AI features (scan, voice, categories, spending tips) ask Claude first. When Claude fails (down, overloaded, no answer within 25 s, not configured, or an answer in the wrong shape), the same request goes to OpenAI if `OPENAI_API_KEY` is set. A refusal is final and is not sent to OpenAI. Requests to OpenAI are sent with `store: false`. Code: `apps/web/src/ai/generate.ts`.
- The server picks credentials in this order: `FIRESTORE_EMULATOR_HOST`, then the three `FIREBASE_*` fields, then `GOOGLE_APPLICATION_CREDENTIALS`. With none of them set it fails with a clear error; it never falls back to a local gcloud login.
- `FIREBASE_PRIVATE_KEY` may contain literal `\n` sequences; they are turned into newlines.
- Firestore needs no indexes or rules files for this app: the server uses only single-field queries, and the admin SDK is not subject to security rules.

#### Data layout
- `users/{userId}` with `email`, `emailLower`, bcrypt `password`, profile fields, `createdAt`, `updatedAt`.
- `userEmails/{sha256(emailLower)}` holds `{ userId }`, so an email can only be registered once in any letter case.
- `users/{userId}/transactions/{id}`: phone rows use their `clientId` as the id, web rows get an auto id.
- `users/{userId}/budgets/{sha256(category)}`, `users/{userId}/categories/{id}` and global `categories/{slug}`.
- `rateLimits/{sha256(key)}` for login and scan rate limiting. Expired windows are reset on the next attempt.

### Running the Application
#### Development Mode
```bash
npm run dev
```
This starts Next.js on http://localhost:9002 and Genkit dev server concurrently. Open [http://localhost:9002](http://localhost:9002) in your browser.

#### Seed Categories (Optional)
Creates the default global categories; it never overwrites existing ones, so it is safe to rerun.
```bash
npm run seed:categories
```

#### Firestore emulator
For local development without a Firebase project, run the emulator (needs Java 21+, the minimum for firebase-tools 15; the script uses `JAVA_HOME`, else a JDK 21+ from `/usr/libexec/java_home`, else Android Studio's bundled JDK):
```bash
npm run emulator
```
Then start the app with `FIRESTORE_EMULATOR_HOST=127.0.0.1:8085` and `FIREBASE_PROJECT_ID=demo-budgetwise`. `demo-` projects exist only in the emulator.

#### Tests
```bash
npm test
```
The test run starts the Firestore emulator itself (or reuses one already running on port 8085) and clears it between tests.

#### One-time copy from MongoDB
`scripts/copyMongoToFirestore.ts` copies an existing MongoDB database into Firestore, keeping user and transaction ids so the Android app keeps working. It is a dry run unless `--write` is passed, never overwrites existing docs, and prints counts only.
```bash
MONGODB_URI="mongodb+srv://user:password@cluster.example.net/budgetWise" npm run copy:mongo-to-firestore
MONGODB_URI="mongodb+srv://user:password@cluster.example.net/budgetWise" npm run copy:mongo-to-firestore -- --write
```
Run it with the same Firestore variables as the app, before the Firestore version takes traffic.

#### Production Build
```bash
npm run build
npm start
```

### Phone API: scan
`POST /api/v1/scan` reads a shop receipt, a KBZPay (KPay) payment slip or a screenshot of the KPay history list, and returns the entries in it. Nothing is saved: the phone shows the entries for review, then saves the chosen ones with `POST /api/v1/transactions` (adding `source`, and a new `clientId` for entries that have none).

- Auth: `Authorization: Bearer <token>` from `POST /api/v1/auth/token`. 20 scans per user per hour.
- Body: `{ "image": "<base64>", "mediaType": "image/jpeg" | "image/png" | "image/webp" }`. Plain base64 without a `data:` prefix (line breaks are ignored), at most 3 MB decoded, which keeps the request under Vercel's 4.5 MB body limit. The phone shrinks photos first (2000 px on the long edge, JPEG quality 90).
- Errors, in the usual `{ "error": { "code", "message" } }` shape: 401 `unauthorized`, 413 `image_too_large`, 422 `validation_failed` (missing field or another media type) or `invalid_image` (not base64, or the bytes are not the declared type), 429 `rate_limited` with `Retry-After`, 502 `scan_failed` when neither Claude nor the OpenAI fallback can read it.
- `kind` is `receipt`, `payment`, `history` or `unknown` (no entries). A receipt gives one expense per category, with its line items in `items`; a slip gives one entry; a history screenshot gives one entry per transaction (received is income, paid or sent is expense).
- Amounts are whole kyat. Categories are the user's own, else `Other`; income is always `Income`. Entries whose amount cannot be read are left out, not guessed.
- Dates: a receipt or slip with no readable date gets today's date in Myanmar and `"dateGuessed": true`, so the review screen can point it out. A history row without a readable date is left out.
- An entry with a KPay transaction id gets `reference` and a fixed `clientId` (sha256 of `kpay:` + reference, shaped as a UUID v4), so saving the same transaction twice stores it once.

```json
{
  "kind": "history",
  "entries": [
    { "type": "income", "amount": 150000, "category": "Income", "description": "ဦးမောင်မောင်", "date": "2026-10-25",
      "reference": "01003456789012345678", "clientId": "8757c8d9-1367-4ee0-b09b-80b464aba7a0" },
    { "type": "expense", "amount": 2500, "category": "Other", "description": "Yangon Bakehouse", "date": "2026-10-23" }
  ]
}
```

### Phone API: voice
`POST /api/v1/voice/parse` turns what the phone's on-device speech models heard into entries to review, or a question about the user's spending. Nothing is saved or answered here: the phone saves the chosen entries with `POST /api/v1/transactions` and answers questions from its own data. No audio is sent.

- Auth: `Authorization: Bearer <token>`. 60 requests per user per hour, counted only for valid requests.
- Body: `{ "transcripts": ["...", "..."] }`, 1 to 3 transcripts of the same utterance from different speech models (each at most 500 characters after trimming, at least one non-empty). The AI compares them, since each model makes different mistakes.
- Errors: 401 `unauthorized`, 422 `validation_failed`, 429 `rate_limited` with `Retry-After`, 502 `parse_failed` when neither Claude nor the OpenAI fallback answers usefully.
- Response: `intent` is `add`, `question` or `other`; `heard` is what the AI understood, in Burmese. `add` carries `entries` shaped like the scan route's (whole kyat, the user's categories else `Other`, income is `Income`, `dateGuessed: true` with today's date when no date was said, no `items`, `reference` or `clientId`). An `add` with no valid entry becomes `other`.
- `question` is `{ "metric": "list" | "total" | "over_budget", "category": string | null, "from": "YYYY-MM-DD", "to": "YYYY-MM-DD" }` with `entries: []`. A question with an impossible period (not a real date, `from` after `to`, before 2000, after tomorrow) or an unknown category is dropped and the intent becomes `other`.

```json
{
  "intent": "add",
  "heard": "ဒီနေ့ ကော်ဖီ သုံးထောင်",
  "entries": [{ "type": "expense", "amount": 3000, "category": "Food", "description": "ကော်ဖီ", "date": "2026-10-30" }],
  "question": null
}
```

### Deployment
- **Vercel**: Push to GitHub and connect to Vercel; it auto-detects Next.js. Set `FIREBASE_PROJECT_ID`, `FIREBASE_CLIENT_EMAIL`, `FIREBASE_PRIVATE_KEY` and `JWT_SECRET` (plus `ANTHROPIC_API_KEY` for AI features, and `OPENAI_API_KEY` for the fallback) in the Vercel dashboard.
- Ensure Genkit is built: `npm run genkit:build` before deploy.

## 🤝 Contributing
Contributions welcome! Fork the repo, create a feature branch, and submit a PR. For major changes, open an issue first.

## 📄 License
MIT License. See [LICENSE](LICENSE) for details.

---

*Built with passion for clean code and innovative finance tools. 🚀*
