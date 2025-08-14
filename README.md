# BudgetWise 💰📊✨

BudgetWise is your intelligent personal finance companion, designed to help you effortlessly track your expenses, manage your budgets, and gain insights into your spending habits. With AI-powered suggestions and a user-friendly interface, taking control of your finances has never been easier!

## ✨ Features

*   **Intuitive Dashboard:** Get a quick overview of your financial health. 📈
*   **Transaction Tracking:** Easily add and categorize your income and expenses. 💸
*   **Budget Management:** Set and monitor spending limits for different categories. 🎯
*   **AI-Powered Insights:** Receive smart suggestions to optimize your spending. 🧠
*   **Category Management:** Organize your transactions with custom categories. 🏷️
*   **Secure Authentication:** Safe and reliable user login and signup. 🔒
*   **Responsive Design:** Access your finances on any device. 📱💻

## 🚀 Technologies Used

*   **Next.js:** React framework for production-grade applications. ⚛️
*   **TypeScript:** Strongly typed JavaScript for enhanced code quality. 📘
*   **MongoDB:** NoSQL database for flexible data storage. 🍃
*   **Mongoose:** MongoDB object data modeling (ODM) for Node.js.  ODM
*   **NextAuth.js:** Authentication for Next.js applications. 🔑
*   **Tailwind CSS:** Utility-first CSS framework for rapid UI development. 🎨
*   **Shadcn/ui:** Reusable UI components built with Tailwind CSS. 🧩
*   **Genkit:** AI framework for building intelligent applications. 🤖
*   **Bcrypt.js:** Library for hashing passwords. 🛡️

## 🛠️ Getting Started

Follow these steps to set up and run BudgetWise on your local machine.

### Prerequisites

*   Node.js (v18 or higher)
*   npm or Yarn
*   MongoDB instance (local or cloud-hosted)

### Installation

1.  **Clone the repository:**
    ```bash
    git clone https://github.com/your-username/budgetwise.git
    cd budgetwise
    ```
2.  **Install dependencies:**
    ```bash
    npm install
    # or yarn install
    ```

### Environment Variables

Create a `.env.local` file in the root of the project and add the following environment variables:

```
MONGODB_URI="your_mongodb_connection_string"
JWT_SECRET="a_very_long_and_random_secret_key_at_least_32_characters_long"
GOOGLE_API_KEY="your_google_api_key_for_genkit"
```

*   Replace `your_mongodb_connection_string` with your MongoDB connection URI.
*   Replace `a_very_long_and_random_secret_key_at_least_32_characters_long` with a strong, unique secret key for JWT encryption.
*   Replace `your_google_api_key_for_genkit` with your Google API Key if you plan to use Genkit AI features.

### Running the Application

#### Development Mode

```bash
npm run dev
# or yarn dev
```
Open [http://localhost:3000](http://localhost:3000) in your browser.

#### Production Build

```bash
npm run build
npm start
```
The application will be served on the port specified in your environment (default is 3000).

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a pull request or open an issue.

## 📄 License

This project is licensed under the MIT License. See the `LICENSE` file for details.