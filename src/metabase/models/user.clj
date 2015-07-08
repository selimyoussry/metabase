(ns metabase.models.user
  (:require [cemerick.friend.credentials :as creds]
            [korma.core :refer :all, :exclude [defentity]]
            [metabase.db :refer :all]
            [metabase.email.messages :as email]
            [metabase.models.interface :refer :all]
            [metabase.util :as u]))

;; ## Enity + DB Multimethods

(defentity User
  [(table :core_user)
   (default-fields id email date_joined first_name last_name last_login is_superuser)
   (hydration-keys author creator user)]

  (pre-insert [_ {:keys [email password] :as user}]
    (assert (u/is-email? email))
    (assert (and (string? password)
                 (not (clojure.string/blank? password))))
    (assert (not (:password_salt user))
            "Don't try to pass an encrypted password to (ins User). Password encryption is handled by pre-insert.")
    (let [salt (.toString (java.util.UUID/randomUUID))
          defaults {:date_joined  (u/new-sql-timestamp)
                    :last_login   (u/new-sql-timestamp)
                    :is_staff     true
                    :is_active    true
                    :is_superuser false}]
      ;; always salt + encrypt the password before put new User in the DB
      (merge defaults user {:password_salt salt
                            :password (creds/hash-bcrypt (str salt password))})))

  (pre-update [_ {:keys [email] :as user}]
    (when email
      (assert (u/is-email? email)))
    user)

  (post-select [_ user]
    (assoc user :common_name (str (:first_name user) " " (:last_name user))))

  (pre-cascade-delete [_ {:keys [id]}]
    (cascade-delete 'metabase.models.session/Session :user_id id)))


(def ^:const current-user-fields
  "The fields we should return for `*current-user*` (used by `metabase.middleware.current-user`)"
  (concat (:metabase.models.interface/default-fields User)
          [:is_active
           :is_staff])) ; but not `password` !

;; ## Related Functions

(defn create-user
  "Convenience function for creating a new `User` and sending out the welcome email."
  [first-name last-name email-address & {:keys [send-welcome reset-url]
                                         :or {send-welcome false}}]
  {:pre [(string? first-name)
         (string? last-name)
         (string? email-address)]}
  (when-let [new-user (ins User
                        :email email-address
                        :first_name first-name
                        :last_name last-name
                        :password (str (java.util.UUID/randomUUID)))]
    (if send-welcome
      (email/send-new-user-email first-name email-address reset-url))
    ;; return the newly created user
    new-user))

(defn set-user-password
  "Updates the stored password for a specified `User` by hashing the password with a random salt."
  [user-id password]
  (let [salt (.toString (java.util.UUID/randomUUID))
        password (creds/hash-bcrypt (str salt password))]
    ;; NOTE: any password change expires the password reset token
    (upd User user-id
      :password_salt salt
      :password password
      :reset_token nil
      :reset_triggered nil)))
