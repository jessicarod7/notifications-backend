-- TODO add slack migration

UPDATE template
SET data = '{"@type": "MessageCard", "@context": "http://schema.org/extensions", "summary": "Red Hat notification", "sections": [{"activityTitle": "Instant notification - {data.application} - {data.bundle}", "activitySubtitle": "{#if data.context.display_name??}The following host triggered events{#else}{data.events.size()} event{#if data.events.size() > 1}s{/if} triggered{/if}"{#if data.context.display_name??}, "facts": [{"name": "Host", "value": "[{data.context.display_name}]({data.inventory_url})"}, {"name": "Events", "value": "{data.events.size()}"}]{/if}}], "potentialAction": [{"@type": "ActionCard", "name": "Open {data.application}", "actions": [{"@type": "OpenUri", "name": "Open {data.application}", "targets": [{"os": "default", "uri": "{data.application_url}"}]}]}]}'
WHERE name = 'generic-teams';

UPDATE template
SET data = '{"text":"{#if data.context.display_name??}<{data.inventory_url}|{data.context.display_name}> triggered {data.events.size()} event{#if data.events.size() > 1}s{/if}{#else}{data.events.size()} event{#if data.events.size() > 1}s{/if} triggered{/if} from {data.bundle}/{data.application}. <{data.application_url}|Open {data.application}>"}'
WHERE name = 'generic-google-chat'