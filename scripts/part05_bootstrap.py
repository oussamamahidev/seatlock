#!/usr/bin/env python3
"""Complete demo profiles, include token roles, and grant Booking machine access.
Required because service-account role assignment in realm imports varies by Keycloak version.
Requires Keycloak to have imported the supplied seatlock-realm.json.
"""
import json, os, sys, time, urllib.parse, urllib.request, urllib.error
from pathlib import Path
BASE=os.environ.get('KEYCLOAK_URL','http://localhost:8090').rstrip('/')
ADMIN=os.environ.get('KEYCLOAK_ADMIN_USERNAME','admin')
PASSWORD=os.environ.get('KEYCLOAK_ADMIN_PASSWORD','dev_only_keycloak_admin')

def http(path, method='GET', data=None, token=None, form=False):
    raw=None
    headers={}
    if data is not None:
        if form:
            raw=urllib.parse.urlencode(data).encode(); headers['Content-Type']='application/x-www-form-urlencoded'
        else:
            raw=json.dumps(data).encode(); headers['Content-Type']='application/json'
    if token: headers['Authorization']='Bearer '+token
    req=urllib.request.Request(BASE+path, data=raw, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=10) as res:
        content=res.read()
        return json.loads(content) if content else None

for attempt in range(25):
    try:
        master=http('/realms/master/protocol/openid-connect/token','POST',{'client_id':'admin-cli','grant_type':'password','username':ADMIN,'password':PASSWORD},form=True)
        break
    except (urllib.error.URLError,ValueError):
        time.sleep(2)
else: sys.exit('Keycloak not ready or wrong admin password. See docker compose logs keycloak')

try:
    token=master['access_token']
    realm=http('/admin/realms/seatlock',token=token)
    # Startup imports skip an existing realm, so complete older demo profiles here.
    realm_file=Path(__file__).resolve().parents[1]/'infra/keycloak/seatlock-realm.json'
    realm_config=json.loads(realm_file.read_text())
    demo_users=realm_config['users']
    for demo in demo_users:
        username=demo['username']
        query=urllib.parse.urlencode({'username':username,'exact':'true'})
        users=http('/admin/realms/seatlock/users?'+query,token=token)
        if len(users)!=1:
            sys.exit('Expected one imported demo user: '+username)
        user=users[0]
        fields=('firstName','lastName','email')
        missing={field:demo[field] for field in fields if not user.get(field)}
        if missing:
            profile={field:user.get(field) for field in fields}
            profile.update(missing)
            profile['emailVerified']=user.get('emailVerified',False)
            http('/admin/realms/seatlock/users/'+user['id'],'PUT',profile,token)
            print('PASS Completed demo profile for '+username)
    # Supply scopes omitted from older imports: user identity and assigned roles.
    scopes=http('/admin/realms/seatlock/client-scopes',token=token)
    client_ids=[]
    for client_id in ('seatlock-cli','seatlock-booking-service'):
        query=urllib.parse.urlencode({'clientId':client_id})
        scope_clients=http('/admin/realms/seatlock/clients?'+query,token=token)
        if len(scope_clients)!=1:
            sys.exit('Expected one imported client: '+client_id)
        client_ids.append(scope_clients[0]['id'])
    for scope_name in ('basic','roles'):
        definition=next(scope for scope in realm_config['clientScopes'] if scope['name']==scope_name)
        existing_scopes=[scope for scope in scopes if scope['name']==scope_name]
        if not existing_scopes:
            http('/admin/realms/seatlock/client-scopes','POST',definition,token)
            scopes=http('/admin/realms/seatlock/client-scopes',token=token)
            existing_scopes=[scope for scope in scopes if scope['name']==scope_name]
        if len(existing_scopes)!=1:
            sys.exit('Expected one client scope: '+scope_name)
        scope_id=existing_scopes[0]['id']
        for client_id in client_ids:
            http('/admin/realms/seatlock/clients/'+client_id+
                 '/default-client-scopes/'+scope_id,'PUT',token=token)
    print('PASS Subject and realm roles included in user and machine tokens')
    clients=http('/admin/realms/seatlock/clients?clientId=seatlock-booking-service',token=token)
    if len(clients)!=1: sys.exit('Expected one machine client in imported realm')
    user=http('/admin/realms/seatlock/clients/'+clients[0]['id']+'/service-account-user',token=token)
    role=http('/admin/realms/seatlock/roles/inventory.reserve',token=token)
    http('/admin/realms/seatlock/users/'+user['id']+'/role-mappings/realm','POST',[role],token)
    print('PASS Keycloak realm and machine role configured (no credentials printed)')
except urllib.error.HTTPError as e:
    sys.exit('Keycloak setup failed HTTP '+str(e.code)+' (check realm import and admin auth)')
