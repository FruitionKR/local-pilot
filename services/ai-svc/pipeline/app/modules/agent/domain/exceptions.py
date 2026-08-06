class AgentTurnRouteContractError(ValueError):
    def __init__(self, failures: list[str]) -> None:
        super().__init__("Agent turn route contract failed: " + "; ".join(failures))
        self.failures = failures
