import { AuthFlowProvider } from "@/views/auth/model/AuthFlowContext";

export default function AuthLayout({ children }: { children: React.ReactNode }) {
  return <AuthFlowProvider>{children}</AuthFlowProvider>;
}
