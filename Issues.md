# Project "Dashboard 2.0" - Production Upgrade Plan

**Objective:** To enhance the new user experience and prepare the application for future growth by implementing a public-facing dashboard, a guest sign-in feature, and a more robust authentication architecture.

---

### **Risk Assessment**

*   **High Risk (Mitigated):** **Authentication Flow Changes.** Modifying core authentication and routing logic is inherently risky. A mistake could lock users out or expose protected data.
    *   **Mitigation:** We are mitigating this by adopting an official, well-understood Next.js design pattern (Middleware + Authenticated Layout). The plan is structured, and we will test the core auth flows (Login, Signup, Guest Login, Protected Route Access) thoroughly after implementation.
*   **Medium Risk:** **State Management Complexity.** The introduction of a guest user and a public/private dashboard split adds complexity to the state.
    *   **Mitigation:** Our `AuthContext` is already designed to handle this. The `handleLogin` function can manage different user types (real vs. guest) without significant changes. We will rely on this centralized logic to manage the complexity.
*   **Low Risk:** **Scope Creep.** The original request was to fix auth checks. We have expanded this to a feature release.
    *   **Mitigation:** The profile picture upload feature was wisely dropped. The remaining features (Dummy Dashboard, Guest Login) are tightly coupled to the necessary auth rearchitecture, making them a natural and efficient extension rather than scope creep.

---

### **Execution Plan**

The plan is broken into two sequential phases. Phase 1 is the foundation and must be completed first.

#### **Phase 1: Foundational - Authentication & Layout Rearchitecture**

*   **Objective:** Eliminate redundant code, fix UI flicker, and create a clean separation between public and private application sections.
*   **Key Actions:**
    1.  **Create `AuthenticatedLayout` Component:** This will be the single client-side component responsible for checking auth status and displaying a full-page loading UI, preventing content flash.
    2.  **Establish `(authenticated)` Route Group:** Create a new directory `src/app/(authenticated)/` to house all protected routes.
    3.  **Create Root Layout for Group:** Add a `layout.tsx` inside the route group that uses the `AuthenticatedLayout` component.
    4.  **Migrate & Refactor Protected Routes:**
        *   Move `src/app/budgets/` and `src/app/myprofile/` into `src/app/(authenticated)/`.
        *   Move the content of `src/app/page.tsx` to a new file: `src/app/(authenticated)/dashboard/page.tsx`.
        *   Remove the old `useEffect`-based auth checks from all moved pages.

#### **Phase 2: Feature Implementation**

*   **Objective:** Build the new user-facing features on top of the solid foundation from Phase 1.
*   **Key Actions:**
    1.  **Implement Public Dummy Dashboard (`/`):**
        *   The now-empty `src/app/page.tsx` will be rebuilt as the public-facing dummy dashboard.
        *   It will feature static components and prominent "Login," "Sign Up," and "Sign in as Guest" buttons.
    2.  **Implement "Sign in as Guest" Functionality:**
        *   Create a new `signInAsGuest` server action in `src/app/actions.ts`. This action will generate a temporary, non-persistent guest user and return a signed JWT for them.
        *   Wire the "Sign in as Guest" button to call this action. The client will then use the existing `handleLogin` flow to create the session and redirect to `/dashboard`.
    3.  **Enhance User Profile Page:**
        *   Add a `createdAt` field to the `User` model in `src/lib/models/user.ts` using Mongoose's `timestamps: true` option.
        *   Display the user's account creation date on the `myprofile` page.
    4.  **Finalize Routing and Navigation:**
        *   Update `middleware.ts` to treat `/` as a public route and redirect logged-in users to `/dashboard`.
        *   Update all navigation elements (e.g., `AppHeader`) to ensure they point to the correct new routes.