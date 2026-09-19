package com.whereismy.money

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage

val supabaseClient = createSupabaseClient(
    supabaseUrl = BuildConfig.SUPABASE_URL,
    supabaseKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
) {
    install(Auth)
    install(Postgrest)
    install(Storage)
    install(Realtime)
}
