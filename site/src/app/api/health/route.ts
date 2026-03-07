export async function GET() {
  return Response.json({
    status: 'ok',
    service: 'gcc-api',
    version: '0.1.0',
  })
}
