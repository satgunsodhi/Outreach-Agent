import { NextResponse } from 'next/server';
import { query } from '@/lib/db';

// Simple authentication check using an environment variable
function isAuthenticated(request) {
  const authHeader = request.headers.get('authorization');
  if (!authHeader) return false;
  
  // Basic Auth is "Basic base64(user:pass)"
  try {
    const base64Credentials = authHeader.split(' ')[1];
    const credentials = Buffer.from(base64Credentials, 'base64').toString('utf-8');
    const [_, password] = credentials.split(':');
    
    // Compare with server's ADMIN_PASSWORD
    return password === process.env.ADMIN_PASSWORD;
  } catch (e) {
    return false;
  }
}

export async function GET(request) {
  if (!isAuthenticated(request)) {
    return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });
  }

  try {
    const result = await query('SELECT * FROM outreach_targets ORDER BY created_at DESC');
    
    // Convert snake_case to camelCase for the frontend to match the old Java API
    const targets = result.rows.map(row => ({
      id: row.id,
      companyName: row.company_name,
      recipientEmail: row.recipient_email,
      jobUrl: row.job_url,
      jobDescription: row.job_description,
      status: row.status,
      retryCount: row.retry_count || 0,
      createdAt: row.created_at,
    }));

    return NextResponse.json(targets);
  } catch (error) {
    console.error('DB Error:', error);
    return NextResponse.json({ error: 'Internal Server Error' }, { status: 500 });
  }
}

export async function POST(request) {
  if (!isAuthenticated(request)) {
    return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });
  }

  try {
    const body = await request.json();
    const { companyName, recipientEmail, jobUrl, jobDescription } = body;

    // Check for duplicates
    const checkResult = await query(
      'SELECT id FROM outreach_targets WHERE company_name = $1 AND recipient_email = $2',
      [companyName, recipientEmail]
    );

    if (checkResult.rowCount > 0) {
      return NextResponse.json({ error: 'Target with this company and email already exists.' }, { status: 400 });
    }

    // Insert new target
    const insertResult = await query(
      `INSERT INTO outreach_targets 
       (company_name, recipient_email, job_url, job_description, status, created_at, retry_count) 
       VALUES ($1, $2, $3, $4, 'PENDING', NOW(), 0) 
       RETURNING *`,
      [companyName, recipientEmail, jobUrl, jobDescription]
    );

    const row = insertResult.rows[0];
    const target = {
      id: row.id,
      companyName: row.company_name,
      recipientEmail: row.recipient_email,
      jobUrl: row.job_url,
      jobDescription: row.job_description,
      status: row.status,
      retryCount: row.retry_count,
      createdAt: row.created_at,
    };

    return NextResponse.json(target);
  } catch (error) {
    console.error('DB Error:', error);
    return NextResponse.json({ error: 'Internal Server Error' }, { status: 500 });
  }
}
