# BudgetWise 💰📊✨

BudgetWise is a modern, AI-powered personal finance application built with Next.js and Firebase. It empowers users to track expenses, manage budgets, visualize spending patterns, and receive intelligent insights—all in a secure, responsive interface. This project showcases full-stack development skills, including server-side rendering, real-time data handling, and seamless AI integration for enhanced user experience.

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
   cd BudgetWise
   ```
2. **Install dependencies:**
   ```bash
   npm install
   ```

### Environment Variables
All data lives in Cloud Firestore, accessed on the server with `firebase-admin` (`src/lib/firestore.ts`). Create a `.env.local` file in the project root (it is git-ignored) with placeholder values replaced:
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
```
- The server picks credentials in this order: `FIRESTORE_EMULATOR_HOST`, then the three `FIREBASE_*` fields, then `GOOGLE_APPLICATION_CREDENTIALS`. With none of them set it fails with a clear error; it never falls back to a local gcloud login.
- `FIREBASE_PRIVATE_KEY` may contain literal `\n` sequences; they are turned into newlines.
- Firestore needs no indexes or rules files for this app: the server uses only single-field queries, and the admin SDK is not subject to security rules.

#### Data layout
- `users/{userId}` with `email`, `emailLower`, bcrypt `password`, profile fields, `createdAt`, `updatedAt`.
- `userEmails/{sha256(emailLower)}` holds `{ userId }`, so an email can only be registered once in any letter case.
- `users/{userId}/transactions/{id}`: phone rows use their `clientId` as the id, web rows get an auto id.
- `users/{userId}/budgets/{sha256(category)}`, `users/{userId}/categories/{id}` and global `categories/{slug}`.
- `rateLimits/{sha256(key)}` for login rate limiting. Expired windows are reset on the next attempt.

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

### Deployment
- **Vercel**: Push to GitHub and connect to Vercel; it auto-detects Next.js. Set `FIREBASE_PROJECT_ID`, `FIREBASE_CLIENT_EMAIL`, `FIREBASE_PRIVATE_KEY` and `JWT_SECRET` (plus `ANTHROPIC_API_KEY` for AI features) in the Vercel dashboard.
- Ensure Genkit is built: `npm run genkit:build` before deploy.

## 🤝 Contributing
Contributions welcome! Fork the repo, create a feature branch, and submit a PR. For major changes, open an issue first.

## 📄 License
MIT License. See [LICENSE](LICENSE) for details.

---

*Built with passion for clean code and innovative finance tools. 🚀*
