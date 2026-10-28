# BudgetWise 💰📊✨

BudgetWise is a modern, AI-powered personal finance application built with Next.js and Firebase. It empowers users to track expenses, manage budgets, visualize spending patterns, and receive intelligent insights—all in a secure, responsive interface. This project showcases full-stack development skills, including server-side rendering, real-time data handling, and seamless AI integration for enhanced user experience.

## Repository layout

- `apps/web/`: the Next.js web app and the `/api/v1` mobile API (deployed on Vercel). Run the npm commands below from here.
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
- **Type-Safe Data Models**: Custom TypeScript models for users, budgets, categories, and transactions (`src/lib/models/`), ensuring robust data integrity.
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
Create a `.env.local` file in the project root with Firebase configuration:
```
FIREBASE_PROJECT_ID=your-project-id
FIREBASE_PRIVATE_KEY="-----BEGIN PRIVATE KEY-----\n...\n-----END PRIVATE KEY-----\n"
FIREBASE_CLIENT_EMAIL=your-client-email@project.iam.gserviceaccount.com
GOOGLE_API_KEY=your-google-api-key-for-genkit
NEXTAUTH_SECRET=your-nextauth-secret (if using additional auth)
JWT_SECRET=at-least-32-characters-long (required for the mobile API under /api/v1)
```
- Set up Firebase: Create a project at [console.firebase.google.com](https://console.firebase.google.com), enable Authentication and Firestore.
- Download service account key JSON and extract values above.
- For AI features, get a Google API key from [makersuite.google.com](https://makersuite.google.com).

### Running the Application
#### Development Mode
```bash
npm run dev
```
This starts Next.js on http://localhost:9002 and Genkit dev server concurrently. Open [http://localhost:9002](http://localhost:9002) in your browser.

#### Seed Categories (Optional)
```bash
npm run seed:categories
```

#### Production Build
```bash
npm run build
npm start
```

### Deployment
- **Vercel**: Push to GitHub and connect to Vercel; it auto-detects Next.js. Configure Firebase env vars in Vercel dashboard.
- Ensure Genkit is built: `npm run genkit:build` before deploy.

## 🤝 Contributing
Contributions welcome! Fork the repo, create a feature branch, and submit a PR. For major changes, open an issue first.

## 📄 License
MIT License. See [LICENSE](LICENSE) for details.

---

*Built with passion for clean code and innovative finance tools. 🚀*
