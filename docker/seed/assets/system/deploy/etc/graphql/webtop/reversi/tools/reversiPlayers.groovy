// The MCP tool reversi_players (tools.yml): the users a game can be offered
// to, as the app's opponent search finds them (searchPrincipals without
// groups, roles, service accounts and the caller), and without the
// anonymous user, who cannot sign in to answer.
//
// Runs as the caller; searchPrincipals answers what it answers the app.
// reversiInvite checks the user again, so a stale id is refused there.
List found = (graphql.data(
	'query ($keyword: String, $limit: Int) { searchPrincipals(keyword: $keyword, limit: $limit) { identifier displayName isGroup isRole isService } }',
	[keyword: (args.keyword ?: '') as String, limit: 40]).searchPrincipals ?: []) as List;
Set<String> excluded = [mcp.userId, 'anonymous'] as Set<String>;
return found.findAll { Map p -> !p.isGroup && !p.isRole && !p.isService && !(p.identifier in excluded); }
	.take(20)
	.collect { Map p -> [id: p.identifier, displayName: p.displayName]; };
