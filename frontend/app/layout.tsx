import type { Metadata } from "next";
import { Providers } from "./providers";
import "./_styles/globals.css";

export const metadata: Metadata = {
  title: "Fruition Agent",
  description: "Research workspace prototype"
};

export default function RootLayout({
  children
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="ko">
      <body>
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
