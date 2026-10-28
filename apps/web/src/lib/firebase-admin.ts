import * as admin from 'firebase-admin';

// This file is no longer used for authentication but can be kept for other admin tasks
// like sending notifications or accessing other Firebase services from the backend.
// The previous initialization code has been removed to prevent server errors when
// environment variables for the service account are not set.

export { admin };
