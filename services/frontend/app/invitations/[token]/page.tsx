import InvitationPage from "@/views/invitation/ui/InvitationPage";

export default async function Page({ params }: { params: Promise<{ token: string }> }) {
  const { token } = await params;
  return <InvitationPage token={token} />;
}
