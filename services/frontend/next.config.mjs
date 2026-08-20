/** @type {import('next').NextConfig} */
// 백엔드 분리: 인증·워크스페이스 CRUD는 access-svc(8081), 나머지 기능 경로는 document-svc(8080).
const accessUrl = process.env.NEXT_PUBLIC_ACCESS_URL || "http://localhost:8081";
const documentUrl = process.env.NEXT_PUBLIC_BACKEND_URL || "http://localhost:8080";

const nextConfig = {
  async rewrites() {
    // 배열 순서대로 먼저 매칭되는 규칙이 적용된다.
    return [
      {
        source: "/api/auth/:path*",
        destination: `${accessUrl}/api/auth/:path*`
      },
      {
        source: "/api/workspaces",
        destination: `${accessUrl}/api/workspaces`
      },
      {
        // 워크스페이스 자체 CRUD·휴지통·복구는 access-svc. 그 밖의 하위 기능은 document-svc가 받는다.
        source: "/api/workspaces/:wid",
        destination: `${accessUrl}/api/workspaces/:wid`
      },
      {
        source: "/api/workspaces/:wid/restore",
        destination: `${accessUrl}/api/workspaces/:wid/restore`
      },
      {
        source: "/api/:path*",
        destination: `${documentUrl}/api/:path*`
      }
    ];
  }
};

export default nextConfig;
