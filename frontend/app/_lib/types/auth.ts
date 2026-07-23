// GET /api/auth/me 응답
export type UserMeResponse = {
  id: string;
  email: string;
  display_name: string | null;
  created_at: string;
};
