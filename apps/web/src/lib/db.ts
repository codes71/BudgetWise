'use server';
import mongoose from 'mongoose';
import loadConfig from 'next/dist/server/config';

// const config = loadConfig()
// if (!config) {;
//   throw new Error('Failed to load Next.js configuration');
// }
const MONGODB_URI = process.env.MONGODB_URI ;

if (!MONGODB_URI) {
  throw new Error('Please define the MONGODB_URI environment variable inside .env');
}

let cached = global.mongoose;

if (!cached) {
  cached = global.mongoose = { conn: null, promise: null };
}

async function dbConnect() {
  if (cached.conn) {
    return cached.conn;
  }

  if (!cached.promise) {
    const opts = {
      bufferCommands: false,
    };

    cached.promise = mongoose.connect(MONGODB_URI!, opts).then((mongoose) => {
      return mongoose;
    });
  }
  cached.conn = await cached.promise;
  return cached.conn;
}

export default dbConnect;
