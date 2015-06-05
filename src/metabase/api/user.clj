(ns metabase.api.user
  (:require [cemerick.friend.credentials :as creds]
            [compojure.core :refer [defroutes GET DELETE POST PUT]]
            [medley.core :refer [mapply]]
            [metabase.api.common :refer :all]
            [metabase.db :refer [sel upd upd-non-nil-keys exists?]]
            (metabase.models [hydrate :refer [hydrate]]
                             [user :refer [User create-user set-user-password]])
            [metabase.util.password :as password]
            [ring.util.request :as req]))

(defn ^:private check-self-or-superuser
  "Check that USER-ID is `*current-user-id*` or that `*current-user*` is a superuser, or throw a 403."
  [user-id]
  {:pre [(integer? user-id)]}
  (check-403 (or (= user-id *current-user-id*)
                 (:is_superuser @*current-user*))))

(defendpoint GET "/"
  "Fetch a list of all active `Users`. You must be a superuser to do this."
  []
  (check-superuser)
  (sel :many User :is_active true))


(defendpoint POST "/"
  "Create a new `User`."
  [:as {{:keys [first_name last_name email]} :body :as request}]
  {first_name [Required NonEmptyString]
   last_name  [Required NonEmptyString]
   email      [Required Email]}
  (check-superuser)
  (check-400 (not (exists? User :email email :is_active true)))
  (let [password-reset-url (str (java.net.URL. (java.net.URL. (req/request-url request)) "/auth/forgot_password"))]
    (-> (create-user first_name last_name email :send-welcome true :reset-url password-reset-url)
        (hydrate :user :organization))))


(defendpoint GET "/current"
  "Fetch the current `User`."
  []
  (check-404 @*current-user*))


(defendpoint GET "/:id"
  "Fetch a `User`. You must be fetching yourself *or* be a superuser."
  [id]
  (check-self-or-superuser id)
  (check-404 (sel :one User :id id :is_active true)))


(defendpoint PUT "/:id"
  "Update a `User`."
  [id :as {{:keys [email first_name last_name] :as body} :body}]
  {email      [Required Email]
   first_name NonEmptyString
   last_name  NonEmptyString}
  (check-self-or-superuser id)
  (check-404 (exists? User :id id :is_active true))           ; only allow updates if the specified account is active
  (check-400 (not (exists? User :email email :id [not= id]))) ; can't change email if it's already taken BY ANOTHER ACCOUNT
  (check-500 (upd-non-nil-keys User id
                               :email email
                               :first_name first_name
                               :last_name last_name))
  (sel :one User :id id))


(defendpoint PUT "/:id/password"
  "Update a user's password."
  [id :as {{:keys [password old_password]} :body}]
  {password     [Required ComplexPassword]
   old_password Required}
  (check-self-or-superuser id)
  (let-404 [user (sel :one [User :password_salt :password] :id id :is_active true)]
    (check (creds/bcrypt-verify (str (:password_salt user) old_password) (:password user))
      [400 "password mismatch"]))
  (set-user-password id password)
  (sel :one User :id id))


(defendpoint DELETE "/:id"
  "Disable a `User`.  This does not remove the `User` from the db and instead disables their account."
  [id]
  (check-superuser)
  (check-500 (upd User id
                  :is_active false))
  {:success true})


(define-routes)
