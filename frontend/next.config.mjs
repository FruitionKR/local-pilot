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
        // 한 세그먼트(:wid)까지만 access-svc. /api/workspaces/:wid/** 는 아래 규칙으로 document-svc.
        source: "/api/workspaces/:wid",
        destination: `${accessUrl}/api/workspaces/:wid`
      },
      {
        source: "/api/:path*",
        destination: `${documentUrl}/api/:path*`
      }
    ];
  }
};

export default nextConfig;
