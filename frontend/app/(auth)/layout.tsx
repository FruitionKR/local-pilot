import { AuthFlowProvider } from "./AuthFlowContext";

export default function AuthLayout({ children }: { children: React.ReactNode }) {
  return <AuthFlowProvider>{children}</AuthFlowProvider>;
}
